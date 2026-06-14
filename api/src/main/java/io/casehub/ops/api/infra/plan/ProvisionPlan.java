package io.casehub.ops.api.infra.plan;

import java.util.List;
import java.util.Objects;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.context.RiskClassification;

public record ProvisionPlan(
        NodeId nodeId,
        List<PlannedChange> changes,
        RiskClassification risk,
        String humanReadableSummary,
        ToolPlanDetail toolDetail) {

    public ProvisionPlan {
        Objects.requireNonNull(nodeId, "nodeId");
        changes = changes != null ? List.copyOf(changes) : List.of();
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(humanReadableSummary, "humanReadableSummary");
        // toolDetail nullable
    }
}
