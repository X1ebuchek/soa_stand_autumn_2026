package orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

final class Worker {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private static final int PAYMENT_CONCURRENCY = 8;
    private static final int BATCH = 200;

    private final Store store;
    private final PaymentClient payments;
    private final InventoryClient inventory;
    private final Breaker breaker = new Breaker();

    private final Semaphore paymentSlots = new Semaphore(PAYMENT_CONCURRENCY);
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> reserved = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> nextAttemptAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    Worker(Store store, Config cfg) {
        this.store = store;
        this.payments = new PaymentClient(cfg.paymentUrl());
        this.inventory = new InventoryClient(cfg.inventoryUrl());
    }

    void start() {
        Thread thread = new Thread(this::loop, "orders-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<Store.OrderRow> pending = store.pending(BATCH);
                if (breaker.isOpen()) {

                    if (!pending.isEmpty()) {
                        dispatch(pending.get(0), true);
                    }
                } else {
                    for (Store.OrderRow order : pending) {
                        dispatch(order, false);
                    }
                }
            } catch (Exception e) {
                log.warn("воркер: {}", e.toString());
            }
            sleep(50);
        }
    }

    private void dispatch(Store.OrderRow order, boolean force) {
        if (!force && !due(order.id())) {
            return;
        }
        if (!inFlight.add(order.id())) {
            return;
        }
        executor.submit(() -> {
            try {
                process(order);
            } catch (Exception e) {
                log.warn("заказ {}: {}", order.id(), e.toString());
                retryLater(order.id());
            } finally {
                inFlight.remove(order.id());
            }
        });
    }

    private void process(Store.OrderRow order) throws Exception {
        if (order.amountCents() <= 0) {

            inventory.release(order.id());
            store.markRejected(order.id());
            forget(order.id());
            return;
        }

        if (!reserved.contains(order.id())) {
            if (!inventory.reserve(order.id(), order.itemsJson(), Duration.ofSeconds(5))) {
                retryLater(order.id());
                return;
            }
            reserved.add(order.id());
        }

        PaymentClient.Outcome outcome = charge(order);
        if (outcome == null) {
            return;
        }

        switch (outcome) {
            case PAID -> {
                store.markPaid(order.id(), order.eventId(), eventPayload(order));
                forget(order.id());
            }
            case DECLINED -> {
                inventory.release(order.id());
                store.markRejected(order.id());
                forget(order.id());
            }
            default -> retryLater(order.id());
        }
    }

    private PaymentClient.Outcome charge(Store.OrderRow order) throws InterruptedException {
        paymentSlots.acquire();
        try {
            Breaker.Permit permit = breaker.acquire();
            if (permit == null) {
                retryLater(order.id());
                return null;
            }
            boolean accounted = false;
            try {
                PaymentClient.Outcome outcome =
                        payments.charge(order.id(), order.amountCents(), order.chargeKey(), permit.timeout());
                account(permit, outcome);
                accounted = true;
                return outcome;
            } finally {

                if (!accounted) {
                    breaker.neutral(permit);
                }
            }
        } finally {
            paymentSlots.release();
        }
    }

    private void account(Breaker.Permit permit, PaymentClient.Outcome outcome) {
        switch (outcome) {

            case PAID, DECLINED -> breaker.success(permit);

            case TIMEOUT, UNREACHABLE -> breaker.failure(permit);

            case SERVER_ERROR, AMBIGUOUS -> breaker.neutral(permit);
        }
    }

    private static String eventPayload(Store.OrderRow order) {
        byte[] json = Json.bytes(Map.of(
                "id", order.eventId(),
                "type", "order.paid",
                "order_id", order.id(),
                "occurred_at", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        return new String(json, StandardCharsets.UTF_8);
    }

    private boolean due(String orderId) {
        Long at = nextAttemptAt.get(orderId);
        return at == null || at <= nowMs();
    }

    private void retryLater(String orderId) {
        int count = attempts.merge(orderId, 1, Integer::sum);
        long delay = Math.min(100L << Math.min(count - 1, 5), 2_000L);
        nextAttemptAt.put(orderId, nowMs() + delay);
    }

    private void forget(String orderId) {
        attempts.remove(orderId);
        nextAttemptAt.remove(orderId);
        reserved.remove(orderId);
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
