package io.casehub.ops.api.infra;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

public record GenericResourceSpec(String resourceType, JsonNode config) implements InfraNodeSpec {

    public GenericResourceSpec {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(config, "config");
    }
}
