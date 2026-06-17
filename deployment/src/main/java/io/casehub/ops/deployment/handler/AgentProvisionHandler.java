package io.casehub.ops.deployment.handler;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.deployment.DeploymentProviderConfigStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Provisions agent nodes by registering their descriptors with the AgentRegistry
 * and storing their provider configurations.
 * Deprovision removes provider configs; AgentRegistry has no deregister method.
 */
@ApplicationScoped
public class AgentProvisionHandler {

    private final AgentRegistry agentRegistry;
    private final DeploymentProviderConfigStore providerConfigStore;

    /**
     * CDI constructor. Also used by tests with explicit registry and store for stubbing.
     */
    @Inject
    public AgentProvisionHandler(AgentRegistry agentRegistry, DeploymentProviderConfigStore providerConfigStore) {
        this.agentRegistry = agentRegistry;
        this.providerConfigStore = providerConfigStore;
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
                context.tenancyId(),
                spec.briefing()
        );
        agentRegistry.register(descriptor);
        providerConfigStore.store(spec.agentId(), spec.providerConfigs());
        return new ProvisionResult.Success();
    }

    public DeprovisionResult deprovision(AgentNodeSpec spec, DeprovisionContext context) {
        providerConfigStore.remove(spec.agentId());
        return new DeprovisionResult.Success();
    }
}
