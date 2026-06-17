package io.casehub.ops.api.deployment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderConfig(String providerName, Map<String, Object> config) {
    public ProviderConfig {
        if (providerName == null || providerName.isBlank())
            throw new IllegalArgumentException("providerName is required");
        config = config != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(config))
                : Map.of();
    }
}
