package io.casehub.ops.api.infra;

import java.util.Objects;

import io.casehub.desiredstate.api.NodeSpec;

public record InfraDesiredNodeSpec(InfraNodeSpec resourceSpec, String backendId) implements NodeSpec {

    public InfraDesiredNodeSpec {
        Objects.requireNonNull(resourceSpec, "resourceSpec");
        Objects.requireNonNull(backendId, "backendId");
    }
}
