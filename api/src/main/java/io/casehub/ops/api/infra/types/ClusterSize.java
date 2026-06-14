package io.casehub.ops.api.infra.types;

import java.util.Objects;

public record ClusterSize(int nodes, String storageClass) {

    public ClusterSize {
        Objects.requireNonNull(storageClass, "storageClass");
    }
}
