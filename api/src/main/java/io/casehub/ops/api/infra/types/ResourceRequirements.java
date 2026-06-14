package io.casehub.ops.api.infra.types;

import java.util.Objects;

public record ResourceRequirements(String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit) {

    public ResourceRequirements {
        Objects.requireNonNull(cpuRequest, "cpuRequest");
        Objects.requireNonNull(cpuLimit, "cpuLimit");
        Objects.requireNonNull(memoryRequest, "memoryRequest");
        Objects.requireNonNull(memoryLimit, "memoryLimit");
    }
}
