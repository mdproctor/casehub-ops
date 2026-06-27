package io.casehub.ops.deployment;

import io.casehub.ops.api.deployment.ProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DeploymentProviderConfigStore {
    private final ConcurrentHashMap<String, List<ProviderConfig>> configs = new ConcurrentHashMap<>();

    public void store(String agentId, List<ProviderConfig> providerConfigs) {
        configs.put(agentId, List.copyOf(providerConfigs));
    }

    public List<ProviderConfig> forAgent(String agentId) {
        return configs.getOrDefault(agentId, List.of());
    }

    public Set<String> agentIds() {
        return Collections.unmodifiableSet(configs.keySet());
    }

    public void remove(String agentId) {
        configs.remove(agentId);
    }
}
