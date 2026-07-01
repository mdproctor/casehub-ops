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
    private final ConcurrentHashMap<String, Set<String>> providerToAgents = new ConcurrentHashMap<>();

    // Weakly consistent: compound read-modify-write across configs and providerToAgents is not atomic.
    // Concurrent store() for the same agentId may briefly leave stale entries. Acceptable for provider initialization.
    public void store(String agentId, List<ProviderConfig> providerConfigs) {
        Map<String, ProviderConfig> previous = configs.get(agentId);
        if (previous != null) {
            for (String oldProvider : previous.keySet()) {
                var set = providerToAgents.get(oldProvider);
                if (set != null) {
                    set.remove(agentId);
                }
            }
        }

        var map = new LinkedHashMap<String, ProviderConfig>();
        for (ProviderConfig pc : providerConfigs) {
            if (map.containsKey(pc.providerName())) {
                LOG.warnf("Duplicate providerName '%s' for agent '%s' — last write wins", pc.providerName(), agentId);
            }
            map.put(pc.providerName(), pc);
        }
        configs.put(agentId, Collections.unmodifiableMap(map));

        // Add agent to new provider sets
        for (String providerName : map.keySet()) {
            providerToAgents.computeIfAbsent(providerName, k -> ConcurrentHashMap.newKeySet()).add(agentId);
        }
    }

    public Map<String, ProviderConfig> forAgent(String agentId) {
        return configs.getOrDefault(agentId, Map.of());
    }

    public Set<String> agentIds() {
        return Collections.unmodifiableSet(configs.keySet());
    }

    public void remove(String agentId) {
        Map<String, ProviderConfig> removed = configs.remove(agentId);
        if (removed != null) {
            for (String providerName : removed.keySet()) {
                var set = providerToAgents.get(providerName);
                if (set != null) {
                    set.remove(agentId);
                }
            }
        }
    }

    public Set<String> agentIdsForProvider(String providerName) {
        var set = providerToAgents.get(providerName);
        return set != null ? Set.copyOf(set) : Set.of();
    }
}
