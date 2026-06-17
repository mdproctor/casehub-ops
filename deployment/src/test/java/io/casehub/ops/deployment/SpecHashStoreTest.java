package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecHashStoreTest {

    private SpecHashStore store;

    @BeforeEach
    void setUp() {
        store = new SpecHashStore();
    }

    @Test
    void unknownNodeHasDrifted() {
        NodeId id = new NodeId("agent-1");
        AgentNodeSpec spec = new AgentNodeSpec("agent-1", "Agent", "worker",
            null, null, null, null, null, null, null, null, null,
            List.of(), null, null, null, null, List.of());

        assertThat(store.hasDrifted(id, spec)).isTrue();
    }

    @Test
    void recordedNodeHasNotDrifted() {
        NodeId id = new NodeId("agent-1");
        AgentNodeSpec spec = new AgentNodeSpec("agent-1", "Agent", "worker",
            null, null, null, null, null, null, null, null, null,
            List.of(), null, null, null, null, List.of());

        store.record(id, spec);

        assertThat(store.hasDrifted(id, spec)).isFalse();
    }

    @Test
    void changedSpecHasDrifted() {
        NodeId id = new NodeId("agent-1");
        AgentNodeSpec spec1 = new AgentNodeSpec("agent-1", "Agent", "worker",
            null, null, null, null, null, null, null, null, null,
            List.of(), null, null, null, null, List.of());
        AgentNodeSpec spec2 = new AgentNodeSpec("agent-1", "DifferentName", "worker",
            null, null, null, null, null, null, null, null, null,
            List.of(), null, null, null, null, List.of());

        store.record(id, spec1);

        assertThat(store.hasDrifted(id, spec2)).isTrue();
    }

    @Test
    void removeNodeMakesDriftedAgain() {
        NodeId id = new NodeId("agent-1");
        AgentNodeSpec spec = new AgentNodeSpec("agent-1", "Agent", "worker",
            null, null, null, null, null, null, null, null, null,
            List.of(), null, null, null, null, List.of());

        store.record(id, spec);
        store.remove(id);

        assertThat(store.hasDrifted(id, spec)).isTrue();
    }

    @Test
    void nestedMapChangesDetected() {
        NodeId id = new NodeId("agent-1");
        AgentNodeSpec spec1 = new AgentNodeSpec("agent-1", "Agent", "worker",
            null, null, null, null, null, null, null, null, null,
            List.of(), null, null, null, null, List.of());
        AgentNodeSpec spec2 = new AgentNodeSpec("agent-1", "Agent", "worker",
            null, null, null, null, null, null, null, null, null,
            List.of(), null, null, null, null, List.of(
                new io.casehub.ops.api.deployment.ProviderConfig("test", Map.of("key", "value"))
            ));

        store.record(id, spec1);

        assertThat(store.hasDrifted(id, spec2)).isTrue();
    }
}
