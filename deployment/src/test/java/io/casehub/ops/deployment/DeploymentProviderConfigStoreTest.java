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
        var docker = new ProviderConfig("docker", Map.of("host", "unix:///var/run/docker.sock"));
        var k8s = new ProviderConfig("k8s", Map.of("cluster", "prod"));

        store.store("agent-1", List.of(docker, k8s));

        Map<String, ProviderConfig> result = store.forAgent("agent-1");
        assertThat(result).containsEntry("docker", docker);
        assertThat(result).containsEntry("k8s", k8s);
        assertThat(result).hasSize(2);
    }

    @Test
    void unknownAgentReturnsEmpty() {
        assertThat(store.forAgent("unknown")).isEmpty();
    }

    @Test
    void removeClears() {
        store.store("agent-1", List.of(
            new ProviderConfig("docker", Map.of("host", "unix:///var/run/docker.sock"))
        ));

        store.remove("agent-1");

        assertThat(store.forAgent("agent-1")).isEmpty();
    }

    @Test
    void duplicateProviderNameLastWriteWins() {
        var first = new ProviderConfig("claudony", Map.of("tools", "read"));
        var second = new ProviderConfig("claudony", Map.of("tools", "read,write"));

        store.store("agent-1", List.of(first, second));

        Map<String, ProviderConfig> result = store.forAgent("agent-1");
        assertThat(result).hasSize(1);
        assertThat(result.get("claudony").config()).containsEntry("tools", "read,write");
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
    void storedMapIsUnmodifiable() {
        store.store("agent-1", List.of(
            new ProviderConfig("docker", Map.of("host", "unix:///var/run/docker.sock"))
        ));

        Map<String, ProviderConfig> retrieved = store.forAgent("agent-1");

        assertThatThrownBy(() -> retrieved.put("k8s", new ProviderConfig("k8s", Map.of())))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
