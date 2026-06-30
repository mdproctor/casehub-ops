package io.casehub.ops.api.approval;

import java.util.Objects;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.StepAction;

public record ApprovalPlan(
        NodeId nodeId,
        StepAction action,
        RiskClassification risk,
        String summary,
        String tenancyId,
        NodeSpec originalSpec,
        PlanDetail detail) {

    public ApprovalPlan {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(originalSpec, "originalSpec");
        // detail nullable
    }
}
