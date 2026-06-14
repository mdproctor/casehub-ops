package io.casehub.ops.api.infra.state;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import io.casehub.desiredstate.api.NodeId;

public record DriftReport(
        NodeId nodeId,
        boolean drifted,
        List<DriftedField> drifts,
        Instant detectedAt,
        String backendId) {

    public DriftReport {
        Objects.requireNonNull(nodeId, "nodeId");
        drifts = drifts != null ? List.copyOf(drifts) : List.of();
        Objects.requireNonNull(detectedAt, "detectedAt");
        Objects.requireNonNull(backendId, "backendId");
    }
}
