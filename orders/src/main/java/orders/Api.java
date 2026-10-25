package orders;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

final class Api {

    private static final Logger log = LoggerFactory.getLogger(Api.class);
    private static final int MAX_BODY = 1 << 20;

    private final Store store;
    private final Config cfg;

    Api(Store store, Config cfg) {
        this.store = store;
        this.cfg = cfg;
    }

    void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.port()), 0);
        server.createContext("/", this::handle);

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private void handle(HttpExchange exchange) {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/healthz".equals(path)) {
                json(exchange, 200, Map.of("status", "ok"));
            } else if ("POST".equals(method) && "/orders".equals(path)) {
                createOrder(exchange);
            } else if ("GET".equals(method) && path.startsWith("/orders/")) {
                getOrder(exchange, path.substring("/orders/".length()));
            } else if ("POST".equals(method) && "/webhooks/delivery".equals(path)) {
                delivery(exchange);
            } else if ("GET".equals(method) && path.startsWith("/users/") && path.endsWith("/bonus")) {
                bonus(exchange, path.substring("/users/".length(), path.length() - "/bonus".length()));
            } else {
                json(exchange, 404, Map.of("error", "not_found"));
            }
        } catch (Exception e) {
            log.warn("{} {}: {}", exchange.getRequestMethod(), exchange.getRequestURI(), e.toString());
            try {
                json(exchange, 500, Map.of("error", "internal"));
            } catch (Exception ignored) {

            }
        } finally {
            exchange.close();
        }
    }

    private void createOrder(HttpExchange exchange) throws IOException, SQLException {
        byte[] raw = readBody(exchange);
        if (raw == null) {
            json(exchange, 400, Map.of("error", "bad_request"));
            return;
        }

        Json.OrderRequest request;
        try {
            request = Json.MAPPER.readValue(raw, Json.OrderRequest.class);
        } catch (Exception e) {
            json(exchange, 400, Map.of("error", "bad_request"));
            return;
        }
        if (!valid(request)) {
            json(exchange, 400, Map.of("error", "bad_request"));
            return;
        }

        long amount;
        try {
            amount = 0;
            for (Json.Item item : request.items()) {
                amount = Math.addExact(amount, Math.multiplyExact((long) item.qty(), item.priceCents()));
            }
        } catch (ArithmeticException overflow) {
            json(exchange, 400, Map.of("error", "bad_request"));
            return;
        }

        String id = UUID.randomUUID().toString();
        String itemsJson = new String(Json.bytes(request.items()), StandardCharsets.UTF_8);

        String chargeKey = "pay:" + id;
        String eventId = "ord-" + UUID.randomUUID();

        store.insertOrder(id, request.userId(), amount, itemsJson, chargeKey, eventId);
        json(exchange, 201, Map.of("id", id, "status", "pending"));
    }

    private void getOrder(HttpExchange exchange, String rawId) throws IOException, SQLException {
        String id = URLDecoder.decode(rawId, StandardCharsets.UTF_8);
        Optional<Store.OrderView> found = store.order(id);
        if (found.isEmpty()) {
            json(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        Store.OrderView order = found.get();
        json(exchange, 200, Map.of("id", order.id(), "status", order.status(),
                "amount_cents", order.amountCents()));
    }

    private void delivery(HttpExchange exchange) throws IOException, SQLException {
        byte[] raw = readBody(exchange);
        Json.DeliveryEvent event = null;
        if (raw != null) {
            try {
                event = Json.MAPPER.readValue(raw, Json.DeliveryEvent.class);
            } catch (Exception ignored) {

            }
        }
        if (event == null) {
            json(exchange, 400, Map.of("error", "bad_request"));
            return;
        }
        if (!"delivery.confirmed".equals(event.type())) {

            json(exchange, 204, null);
            return;
        }
        if (event.id() == null || event.id().isBlank()
                || event.userId() == null || event.userId().isBlank()
                || event.bonusPoints() < 1) {
            json(exchange, 400, Map.of("error", "bad_request"));
            return;
        }

        store.accrueBonus(event.id(), event.userId(), event.bonusPoints());

        json(exchange, 204, null);
    }

    private void bonus(HttpExchange exchange, String rawId) throws IOException, SQLException {
        String userId = URLDecoder.decode(rawId, StandardCharsets.UTF_8);
        json(exchange, 200, Map.of("points", store.bonus(userId)));
    }

    private static boolean valid(Json.OrderRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            return false;
        }
        if (request.items() == null || request.items().isEmpty()) {
            return false;
        }
        for (Json.Item item : request.items()) {
            if (item == null || item.sku() == null || item.sku().isBlank()) {
                return false;
            }
            if (item.qty() < 1 || item.priceCents() < 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            return body.length > MAX_BODY ? null : body;
        }
    }

    private static void json(HttpExchange exchange, int code, Object body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(code, -1);
            return;
        }
        byte[] bytes = Json.bytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
