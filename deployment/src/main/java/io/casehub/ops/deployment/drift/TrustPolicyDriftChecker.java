package io.casehub.ops.deployment.drift;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.deployment.NodeDriftChecker;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import io.casehub.ops.deployment.DeploymentTrustRoutingPolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Checks drift for trust policy nodes by comparing policy fields against the provider's current policy.
 * A policy that equals TrustRoutingPolicy.DEFAULT is considered ABSENT.
 */
@ApplicationScoped
public class TrustPolicyDriftChecker implements NodeDriftChecker {
    private final DeploymentTrustRoutingPolicyProvider policyProvider;

    @Inject
    public TrustPolicyDriftChecker(DeploymentTrustRoutingPolicyProvider policyProvider) {
        this.policyProvider = policyProvider;
    }

    @Override
    public String nodeType() {
        return "trust_policy";
    }

    @Override
    public NodeStatus check(NodeSpec spec, String tenancyId) {
        if (!(spec instanceof TrustPolicyNodeSpec tps)) {
            return NodeStatus.UNKNOWN;
        }

        TrustRoutingPolicy actual = policyProvider.forCapability(tps.capability());
        if (actual == TrustRoutingPolicy.DEFAULT) {
            return NodeStatus.ABSENT;
        }

        return policyMatches(tps, actual) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
    }

    private boolean policyMatches(TrustPolicyNodeSpec spec, TrustRoutingPolicy actual) {
        return spec.threshold() == actual.threshold()
                && spec.minimumObservations() == actual.minimumObservations()
                && spec.borderlineMargin() == actual.borderlineMargin()
                && spec.blendFactor() == actual.blendFactor()
                && spec.bootstrapEscalationRequired() == actual.bootstrapEscalationRequired()
                && spec.qualityFloors().equals(actual.qualityFloors());
    }
}
