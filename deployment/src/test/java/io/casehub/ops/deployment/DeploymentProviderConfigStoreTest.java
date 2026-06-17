package io.casehub.ops.deployment;

import io.casehub.ops.api.deployment.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentProviderConfigStoreTest {

    private DeploymentProviderConfigStore store;

    @BeforeEach
    void setUp() {
        store = new DeploymentProviderConfigStore();
    }

    @Test
    void storeAndRetrieve() {
        String agentId = "agent-1";
        List<ProviderConfig> configs = List.of(
            new ProviderConfig("docker", Map.of("host", "unix:///var/run/docker.sock")),
            new ProviderConfig("k8s", Map.of("cluster", "prod"))
        );

        store.store(agentId, configs);

        assertThat(store.forAgent(agentId)).isEqualTo(configs);
    }

    @Test
    void unknownAgentReturnsEmpty() {
        assertThat(store.forAgent("unknown")).isEmpty();
    }

    @Test
    void removeClears() {
        String agentId = "agent-1";
        List<ProviderConfig> configs = List.of(
            new ProviderConfig("docker", Map.of("host", "unix:///var/run/docker.sock"))
        );

        store.store(agentId, configs);
        store.remove(agentId);

        assertThat(store.forAgent(agentId)).isEmpty();
    }

    @Test
    void storedListIsImmutable() {
        String agentId = "agent-1";
        List<ProviderConfig> configs = List.of(
            new ProviderConfig("docker", Map.of("host", "unix:///var/run/docker.sock"))
        );

        store.store(agentId, configs);
        List<ProviderConfig> retrieved = store.forAgent(agentId);

        assertThatThrownBy(() -> retrieved.add(new ProviderConfig("k8s", Map.of())))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
