package io.casehub.ops.api.infra.state;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

public record ResourceOutputs(Map<String, JsonNode> values) {

    public ResourceOutputs {
        values = values != null ? Map.copyOf(values) : Map.of();
    }

    public Optional<JsonNode> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Optional<String> getString(String key) {
        return get(key).filter(JsonNode::isTextual).map(JsonNode::asText);
    }

    public static ResourceOutputs empty() {
        return new ResourceOutputs(Map.of());
    }
}
