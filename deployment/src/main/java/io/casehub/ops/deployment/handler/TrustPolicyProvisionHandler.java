package io.casehub.ops.deployment.handler;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import io.casehub.ops.deployment.DeploymentTrustRoutingPolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Provisions trust policy nodes by storing their routing policies in the DeploymentTrustRoutingPolicyProvider.
 * Deprovision removes the policy, reverting the capability to TrustRoutingPolicy.DEFAULT.
 */
@ApplicationScoped
public class TrustPolicyProvisionHandler {

    private final DeploymentTrustRoutingPolicyProvider policyProvider;

    /**
     * CDI constructor. Also used by tests with explicit provider for stubbing.
     */
    @Inject
    public TrustPolicyProvisionHandler(DeploymentTrustRoutingPolicyProvider policyProvider) {
        this.policyProvider = policyProvider;
    }

    public ProvisionResult provision(TrustPolicyNodeSpec spec, ProvisionContext context) {
        TrustRoutingPolicy policy = new TrustRoutingPolicy(
                spec.threshold(),
                spec.minimumObservations(),
                spec.borderlineMargin(),
                spec.blendFactor(),
                spec.qualityFloors(),
                spec.bootstrapEscalationRequired(),
                null,
                Set.of()
        );
        policyProvider.store(spec.capability(), policy);
        return new ProvisionResult.Success();
    }

    public DeprovisionResult deprovision(TrustPolicyNodeSpec spec, DeprovisionContext context) {
        policyProvider.remove(spec.capability());
        return new DeprovisionResult.Success();
    }
}
