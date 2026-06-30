package io.casehub.ops.deployment;

import io.casehub.ops.api.deployment.ProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DeploymentProviderConfigStore {

    private static final Logger LOG = Logger.getLogger(DeploymentProviderConfigStore.class);

    private final ConcurrentHashMap<String, Map<String, ProviderConfig>> configs = new ConcurrentHashMap<>();

    public void store(String agentId, List<ProviderConfig> providerConfigs) {
        var map = new LinkedHashMap<String, ProviderConfig>();
        for (ProviderConfig pc : providerConfigs) {
            if (map.containsKey(pc.providerName())) {
                LOG.warnf("Duplicate providerName '%s' for agent '%s' — last write wins", pc.providerName(), agentId);
            }
            map.put(pc.providerName(), pc);
        }
        configs.put(agentId, Collections.unmodifiableMap(map));
    }

    public Map<String, ProviderConfig> forAgent(String agentId) {
        return configs.getOrDefault(agentId, Map.of());
    }

    public Set<String> agentIds() {
        return Collections.unmodifiableSet(configs.keySet());
    }

    public void remove(String agentId) {
        configs.remove(agentId);
    }
}
