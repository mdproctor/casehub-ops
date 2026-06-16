package io.casehub.ops.deployment.handler;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Provisions agent nodes by registering their descriptors with the AgentRegistry.
 * Deprovision is a no-op — AgentRegistry has no deregister method.
 */
@ApplicationScoped
public class AgentProvisionHandler {

    private final AgentRegistry agentRegistry;

    /**
     * CDI constructor. Also used by tests with explicit registry for stubbing.
     */
    @Inject
    public AgentProvisionHandler(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    public ProvisionResult provision(AgentNodeSpec spec, ProvisionContext context) {
        AgentDescriptor descriptor = new AgentDescriptor(
                spec.agentId(),
                spec.name(),
                spec.version(),
                spec.provider(),
                spec.modelFamily(),
                spec.modelVersion(),
                spec.weightsFingerprint(),
                spec.domainVocabulary(),
                spec.slotVocabulary(),
                spec.dispositionVocabulary(),
                spec.axisVocabularies(),
                spec.slot(),
                spec.capabilities(),
                spec.disposition(),
                spec.jurisdiction(),
                spec.dataHandlingPolicy(),
                context.tenancyId()
        );
        agentRegistry.register(descriptor);
        return new ProvisionResult.Success();
    }

    public DeprovisionResult deprovision(AgentNodeSpec spec, DeprovisionContext context) {
        // AgentRegistry has no deregister method — deprovision is a no-op
        return new DeprovisionResult.Success();
    }
}
