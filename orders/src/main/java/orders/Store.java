package orders;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Store implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Store.class);

    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS orders (
                id           TEXT PRIMARY KEY,
                user_id      TEXT NOT NULL,
                status       TEXT NOT NULL,
                amount_cents BIGINT NOT NULL,
                currency     TEXT NOT NULL,
                items        JSONB NOT NULL,
                charge_key   TEXT NOT NULL,
                event_id     TEXT NOT NULL,
                created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """,
            "CREATE INDEX IF NOT EXISTS orders_pending_idx ON orders (created_at) WHERE status = 'pending'",
            """
            CREATE TABLE IF NOT EXISTS outbox (
                order_id     TEXT PRIMARY KEY,
                event_id     TEXT NOT NULL,
                payload      JSONB NOT NULL,
                attempts     INT NOT NULL DEFAULT 0,
                next_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                published_at TIMESTAMPTZ
            )
            """,
            "CREATE INDEX IF NOT EXISTS outbox_due_idx ON outbox (next_at) WHERE published_at IS NULL",
            """
            CREATE TABLE IF NOT EXISTS delivery_events (
                event_id    TEXT PRIMARY KEY,
                user_id     TEXT NOT NULL,
                points      BIGINT NOT NULL,
                received_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS bonus_balances (
                user_id TEXT PRIMARY KEY,
                points  BIGINT NOT NULL DEFAULT 0
            )
            """
    };

    public record OrderRow(String id, String userId, long amountCents,
                           String itemsJson, String chargeKey, String eventId) {
    }

    public record OrderView(String id, String status, long amountCents) {
    }

    public record OutboxRow(String orderId, String eventId, String payload, int attempts) {
    }

    private final HikariDataSource pool;

    private Store(HikariDataSource pool) {
        this.pool = pool;
    }

    public static Store connect(Config cfg) throws InterruptedException {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.jdbcUrl());
        if (cfg.dbUser() != null) {
            hc.setUsername(cfg.dbUser());
        }
        if (cfg.dbPassword() != null) {
            hc.setPassword(cfg.dbPassword());
        }

        hc.setMaximumPoolSize(12);
        hc.setMinimumIdle(2);
        hc.setConnectionTimeout(5_000);
        hc.setInitializationFailTimeout(-1);
        hc.setPoolName("orders");

        HikariDataSource pool = new HikariDataSource(hc);

        for (int attempt = 1; ; attempt++) {
            try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
                for (String ddl : SCHEMA) {
                    st.execute(ddl);
                }
                return new Store(pool);
            } catch (SQLException e) {
                if (attempt >= 60) {
                    pool.close();
                    throw new IllegalStateException("база не поднялась: " + e.getMessage(), e);
                }
                log.info("база недоступна ({}), жду", e.getMessage());
                Thread.sleep(1_000);
            }
        }
    }

    public void insertOrder(String id, String userId, long amountCents, String itemsJson,
                            String chargeKey, String eventId) throws SQLException {
        String sql = """
                INSERT INTO orders (id, user_id, status, amount_cents, currency, items, charge_key, event_id)
                VALUES (?, ?, 'pending', ?, 'RUB', ?::jsonb, ?, ?)
                """;
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setLong(3, amountCents);
            ps.setString(4, itemsJson);
            ps.setString(5, chargeKey);
            ps.setString(6, eventId);
            ps.executeUpdate();
        }
    }

    public List<OrderRow> pending(int limit) throws SQLException {
        String sql = """
                SELECT id, user_id, amount_cents, items::text, charge_key, event_id
                  FROM orders
                 WHERE status = 'pending'
                 ORDER BY created_at
                 LIMIT ?
                """;
        List<OrderRow> rows = new ArrayList<>();
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new OrderRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                            rs.getString(4), rs.getString(5), rs.getString(6)));
                }
            }
        }
        return rows;
    }

    public Optional<OrderView> order(String id) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, status, amount_cents FROM orders WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OrderView(rs.getString(1), rs.getString(2), rs.getLong(3)));
            }
        }
    }

    public void markPaid(String id, String eventId, String payload) throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE orders SET status = 'paid', updated_at = now() WHERE id = ? AND status = 'pending'")) {
                    ps.setString(1, id);
                    updated = ps.executeUpdate();
                }
                if (updated == 1) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO outbox (order_id, event_id, payload)
                            VALUES (?, ?, ?::jsonb)
                            ON CONFLICT (order_id) DO NOTHING
                            """)) {
                        ps.setString(1, id);
                        ps.setString(2, eventId);
                        ps.setString(3, payload);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public void markRejected(String id) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE orders SET status = 'rejected', updated_at = now() WHERE id = ? AND status = 'pending'")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    public List<OutboxRow> dueOutbox(int limit) throws SQLException {
        String sql = """
                SELECT order_id, event_id, payload::text, attempts
                  FROM outbox
                 WHERE published_at IS NULL AND next_at <= now()
                 ORDER BY next_at
                 LIMIT ?
                """;
        List<OutboxRow> rows = new ArrayList<>();
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new OutboxRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
                }
            }
        }
        return rows;
    }

    public void outboxPublished(String orderId) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE outbox SET published_at = now(), attempts = attempts + 1 WHERE order_id = ?")) {
            ps.setString(1, orderId);
            ps.executeUpdate();
        }
    }

    public void outboxRetry(String orderId, int attempts, long delayMs) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE outbox SET attempts = ?, next_at = ? WHERE order_id = ? AND published_at IS NULL")) {
            ps.setInt(1, attempts);
            ps.setObject(2, OffsetDateTime.now(ZoneOffset.UTC).plusNanos(delayMs * 1_000_000L));
            ps.setString(3, orderId);
            ps.executeUpdate();
        }
    }

    public boolean accrueBonus(String eventId, String userId, long points) throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO delivery_events (event_id, user_id, points) VALUES (?, ?, ?) "
                                + "ON CONFLICT (event_id) DO NOTHING")) {
                    ps.setString(1, eventId);
                    ps.setString(2, userId);
                    ps.setLong(3, points);
                    inserted = ps.executeUpdate();
                }
                if (inserted == 1) {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO bonus_balances (user_id, points) VALUES (?, ?)
                            ON CONFLICT (user_id) DO UPDATE
                               SET points = bonus_balances.points + EXCLUDED.points
                            """)) {
                        ps.setString(1, userId);
                        ps.setLong(2, points);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return inserted == 1;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public long bonus(String userId) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT points FROM bonus_balances WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
