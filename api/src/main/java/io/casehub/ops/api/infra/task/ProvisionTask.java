package io.casehub.ops.api.infra.task;

import java.util.Objects;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.state.ResourceState;

public record ProvisionTask(
        NodeId nodeId,
        InfraNodeSpec spec,
        TaskAction action,
        ResourceState currentState) {

    public ProvisionTask {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(action, "action");
        // currentState nullable — null for CREATE
    }
}
