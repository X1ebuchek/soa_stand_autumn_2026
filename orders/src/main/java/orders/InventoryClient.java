package orders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

final class InventoryClient {

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private final URI reservations;

    InventoryClient(URI base) {
        this.reservations = URI.create(base + "/reservations");
    }

    boolean reserve(String orderId, String itemsJson, Duration timeout) {
        byte[] body = Json.bytes(Map.of("order_id", orderId, "items", Json.tree(itemsJson)));
        HttpRequest request = HttpRequest.newBuilder(reservations)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 || response.statusCode() == 201;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void release(String orderId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(reservations + "/" + orderId))
                .timeout(Duration.ofSeconds(5))
                .DELETE()
                .build();
        try {
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
