package orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    private static final int BATCH = 32;
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    private static final long MAX_BACKOFF_MS = 5_000;

    private final Store store;
    private final URI events;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private Outbox(Store store, URI base) {
        this.store = store;
        this.events = URI.create(base + "/events");
    }

    static Outbox create(Store store, Config cfg) {
        return cfg.eventsUrl() == null ? null : new Outbox(store, cfg.eventsUrl());
    }

    void start() {
        Thread thread = new Thread(this::loop, "orders-outbox");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<Store.OutboxRow> due = store.dueOutbox(BATCH);
                for (Store.OutboxRow row : due) {
                    if (!inFlight.add(row.orderId())) {
                        continue;
                    }
                    executor.submit(() -> publish(row));
                }
            } catch (Exception e) {
                log.warn("outbox: {}", e.toString());
            }
            sleep(100);
        }
    }

    private void publish(Store.OutboxRow row) {
        try {
            HttpRequest request = HttpRequest.newBuilder(events)
                    .timeout(PUBLISH_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(row.payload(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 202) {
                store.outboxPublished(row.orderId());
            } else {
                log.warn("шина: код {} на событие заказа {}", response.statusCode(), row.orderId());
                retry(row);
            }
        } catch (Exception e) {

            retry(row);
        } finally {
            inFlight.remove(row.orderId());
        }
    }

    private void retry(Store.OutboxRow row) {
        int attempts = row.attempts() + 1;
        long delay = Math.min(100L << Math.min(attempts - 1, 5), MAX_BACKOFF_MS);
        try {
            store.outboxRetry(row.orderId(), attempts, delay);
        } catch (Exception e) {
            log.warn("outbox: не отложил {}: {}", row.orderId(), e.toString());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
