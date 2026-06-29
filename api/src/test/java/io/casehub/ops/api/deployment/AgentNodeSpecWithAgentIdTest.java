package io.casehub.ops.api.deployment;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AgentNodeSpecWithAgentIdTest {

    @Test
    void withAgentIdChangesOnlyAgentId() {
        var original = new AgentNodeSpec(
            "original-id", "Test Agent", "worker", "claude",
            null, null, null, null, null, null, null,
            Map.of(), List.of(), null, null, null, null, List.of());
        var derived = original.withAgentId("derived-id");

        assertEquals("derived-id", derived.agentId());
        assertEquals("derived-id", derived.nodeId());
        assertEquals("Test Agent", derived.name());
        assertEquals("worker", derived.slot());
        assertEquals("claude", derived.provider());
    }

    @Test
    void withAgentIdRejectsNull() {
        var original = new AgentNodeSpec(
            "id", "name", "worker", null,
            null, null, null, null, null, null, null,
            Map.of(), List.of(), null, null, null, null, List.of());
        assertThrows(NullPointerException.class, () -> original.withAgentId(null));
    }
}
