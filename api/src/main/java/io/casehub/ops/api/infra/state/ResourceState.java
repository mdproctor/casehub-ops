package io.casehub.ops.api.infra.state;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import io.casehub.desiredstate.api.NodeId;

public record ResourceState(
        NodeId nodeId,
        String resourceType,
        ResourceStatus status,
        Instant lastObserved,
        JsonNode attributes,
        ResourceOutputs outputs) {

    public ResourceState {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastObserved, "lastObserved");
        // attributes nullable — may not be available
        Objects.requireNonNull(outputs, "outputs");
    }
}
