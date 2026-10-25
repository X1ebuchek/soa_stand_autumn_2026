package orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

final class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    enum Outcome {

        PAID,

        DECLINED,

        SERVER_ERROR,

        TIMEOUT,

        UNREACHABLE,

        AMBIGUOUS
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    private final URI payments;

    PaymentClient(URI base) {
        this.payments = URI.create(base + "/payments");
    }

    Outcome charge(String orderId, long amountCents, String idempotencyKey, Duration timeout) {
        byte[] body = Json.bytes(Map.of(
                "order_id", orderId,
                "amount_cents", amountCents,
                "currency", "RUB"));
        HttpRequest request = HttpRequest.newBuilder(payments)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return switch (code) {
                case 200, 201 -> Outcome.PAID;
                case 402 -> Outcome.DECLINED;
                case 502, 503, 504 -> Outcome.UNREACHABLE;
                default -> {
                    log.warn("payment: код {} на заказ {}", code, orderId);
                    yield Outcome.SERVER_ERROR;
                }
            };
        } catch (HttpTimeoutException e) {

            return Outcome.TIMEOUT;
        } catch (ConnectException e) {
            return Outcome.UNREACHABLE;
        } catch (IOException e) {

            return Outcome.AMBIGUOUS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.AMBIGUOUS;
        }
    }
}
