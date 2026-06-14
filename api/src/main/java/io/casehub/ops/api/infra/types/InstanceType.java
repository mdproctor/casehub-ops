package io.casehub.ops.api.infra.types;

import java.util.Objects;

public record InstanceType(String family, String size) {

    public InstanceType {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(size, "size");
    }
}
