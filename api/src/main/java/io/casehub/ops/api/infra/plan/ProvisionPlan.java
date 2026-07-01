package io.casehub.ops.api.infra.plan;

import java.util.List;
import java.util.Objects;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.approval.PlanDetail;
import io.casehub.ops.api.approval.RiskClassification;

public record ProvisionPlan(
        NodeId nodeId,
        List<PlannedChange> changes,
        RiskClassification risk,
        String summary,
        ToolPlanDetail toolDetail) implements PlanDetail {

    public ProvisionPlan {
        Objects.requireNonNull(nodeId, "nodeId");
        changes = changes != null ? List.copyOf(changes) : List.of();
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(summary, "summary");
        // toolDetail nullable
    }
}
