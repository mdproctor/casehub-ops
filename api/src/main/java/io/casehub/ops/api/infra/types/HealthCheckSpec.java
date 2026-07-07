package io.casehub.ops.api.infra.types;

import java.util.Objects;

public record HealthCheckSpec(String path, int port, int initialDelaySeconds, int periodSeconds) {
    public HealthCheckSpec {
        Objects.requireNonNull(path, "path");
    }
}
