package io.casehub.ops.api.infra.types;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record AnsibleExtraVars(Map<String, String> values) {

    public AnsibleExtraVars {
        Objects.requireNonNull(values, "values");
        values = Map.copyOf(values);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public static AnsibleExtraVars empty() {
        return new AnsibleExtraVars(Map.of());
    }
}
