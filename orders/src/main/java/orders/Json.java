package orders;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;

final class Json {

    static final ObjectMapper MAPPER = JsonMapper.builder()

            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {
    }

    record Item(String sku, int qty, @JsonProperty("price_cents") long priceCents) {
    }

    record OrderRequest(@JsonProperty("user_id") String userId, List<Item> items) {
    }

    record DeliveryEvent(String id, String type,
                         @JsonProperty("user_id") String userId,
                         @JsonProperty("bonus_points") long bonusPoints,
                         @JsonProperty("occurred_at") String occurredAt) {
    }

    static byte[] bytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("не сериализуется: " + value, e);
        }
    }

    static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("не JSON: " + json, e);
        }
    }
}
