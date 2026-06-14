package io.casehub.ops.api.infra.context;

import java.time.Instant;
import java.util.Objects;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.infra.plan.ProvisionPlan;

public record InfraProvisionContext(
        NodeId nodeId,
        String tenancyId,
        ProvisionPhase phase,
        ProvisionAction action,
        ProvisionPlan approvedPlan,
        RiskThresholds thresholds,
        Instant requestedAt) {

    public InfraProvisionContext {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(action, "action");
        // approvedPlan nullable — non-null only for APPLY phase
        Objects.requireNonNull(thresholds, "thresholds");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
