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
    void agentIdsReturnsStoredKeys() {
        store.store("agent-1", List.of(new ProviderConfig("docker", Map.of())));
        store.store("agent-2", List.of(new ProviderConfig("k8s", Map.of())));

        assertThat(store.agentIds()).containsExactlyInAnyOrder("agent-1", "agent-2");
    }

    @Test
    void agentIdsEmptyWhenNoEntries() {
        assertThat(store.agentIds()).isEmpty();
    }

    @Test
    void agentIdsReflectsRemoval() {
        store.store("agent-1", List.of(new ProviderConfig("docker", Map.of())));
        store.store("agent-2", List.of(new ProviderConfig("k8s", Map.of())));
        store.remove("agent-1");

        assertThat(store.agentIds()).containsExactly("agent-2");
    }

    @Test
    void agentIdsIsUnmodifiable() {
        store.store("agent-1", List.of(new ProviderConfig("docker", Map.of())));

        assertThatThrownBy(() -> store.agentIds().add("agent-2"))
            .isInstanceOf(UnsupportedOperationException.class);
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
