package io.casehub.ops.deployment;

import io.casehub.ops.api.deployment.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentProvisionerConfigRegistryTest {

    private DeploymentProviderConfigStore store;
    private DeploymentProvisionerConfigRegistry registry;

    @BeforeEach
    void setUp() {
        store = new DeploymentProviderConfigStore();
        registry = new DeploymentProvisionerConfigRegistry(store);
    }

    @Test
    void configForReturnsConfigWhenAgentAndProviderExist() {
        store.store("agent-1", List.of(
            new ProviderConfig("claudony", Map.of("tools", "read,write"))
        ));

        assertThat(registry.configFor("claudony", "agent-1"))
            .containsEntry("tools", "read,write");
    }

    @Test
    void configForReturnsEmptyForUnknownAgent() {
        assertThat(registry.configFor("claudony", "unknown")).isEmpty();
    }

    @Test
    void configForReturnsEmptyForKnownAgentUnknownProvider() {
        store.store("agent-1", List.of(
            new ProviderConfig("claudony", Map.of("tools", "read"))
        ));

        assertThat(registry.configFor("openclaw", "agent-1")).isEmpty();
    }

    @Test
    void declaredAgentIdsReturnsAgentsWithProvider() {
        store.store("agent-1", List.of(
            new ProviderConfig("claudony", Map.of("tools", "read")),
            new ProviderConfig("openclaw", Map.of("key", "val"))
        ));
        store.store("agent-2", List.of(
            new ProviderConfig("claudony", Map.of("tools", "write"))
        ));
        store.store("agent-3", List.of(
            new ProviderConfig("openclaw", Map.of("key", "other"))
        ));

        assertThat(registry.declaredAgentIds("claudony"))
            .containsExactlyInAnyOrder("agent-1", "agent-2");
        assertThat(registry.declaredAgentIds("openclaw"))
            .containsExactlyInAnyOrder("agent-1", "agent-3");
    }

    @Test
    void declaredAgentIdsReturnsEmptyWhenNoAgentsHaveProvider() {
        store.store("agent-1", List.of(
            new ProviderConfig("claudony", Map.of("tools", "read"))
        ));

        assertThat(registry.declaredAgentIds("nonexistent")).isEmpty();
    }
}
