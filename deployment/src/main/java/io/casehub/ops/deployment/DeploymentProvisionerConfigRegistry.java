package io.casehub.ops.deployment;

import io.casehub.api.spi.ProvisionerConfigRegistry;
import io.casehub.ops.api.deployment.ProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class DeploymentProvisionerConfigRegistry implements ProvisionerConfigRegistry {

    private final DeploymentProviderConfigStore store;

    @Inject
    public DeploymentProvisionerConfigRegistry(DeploymentProviderConfigStore store) {
        this.store = store;
    }

    @Override
    public Map<String, Object> configFor(String providerName, String agentId) {
        ProviderConfig pc = store.forAgent(agentId).get(providerName);
        return pc != null ? pc.config() : Map.of();
    }

    @Override
    public Set<String> declaredAgentIds(String providerName) {
        return store.agentIdsForProvider(providerName);
    }
}
