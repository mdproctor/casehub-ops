package io.casehub.ops.api.infra.types;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record Labels(Map<String, String> values) {

    public Labels {
        Objects.requireNonNull(values, "values");
        values = Map.copyOf(values);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public static Labels of(Map<String, String> values) {
        return new Labels(values);
    }

    public static Labels empty() {
        return new Labels(Map.of());
    }
}
