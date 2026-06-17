package io.casehub.ops.deployment.drift;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentDriftCheckerTest {

    private AgentDriftChecker checker;
    private StubAgentRegistry agentRegistry;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        agentRegistry = new StubAgentRegistry();
        checker = new AgentDriftChecker(agentRegistry);
    }

    @Test
    void nodeType() {
        assertEquals("agent", checker.nodeType());
    }

    @Test
    void agentPresent() {
        var cap = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        var descriptor = new AgentDescriptor(
                "agent-1", "Agent", "1.0", "anthropic", "claude", "4.6", "fp1",
                "domain", "slot", "disp", Map.of(), "worker",
                List.of(cap), null, "US", "policy", TENANCY_ID, null);
        agentRegistry.register(descriptor);

        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(cap), null, "US", "policy", null, List.of());

        assertEquals(NodeStatus.PRESENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void agentAbsent() {
        var cap = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(cap), null, "US", "policy", null, List.of());

        assertEquals(NodeStatus.ABSENT, checker.check(spec, TENANCY_ID));
    }

    @Test
    void agentDrifted_capabilitiesMismatch() {
        var cap1 = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        var cap2 = new AgentCapability("cap-b", null, null, null, List.of(), List.of(), List.of(), Map.of());

        var descriptor = new AgentDescriptor(
                "agent-1", "Agent", "1.0", "anthropic", "claude", "4.6", "fp1",
                "domain", "slot", "disp", Map.of(), "worker",
                List.of(cap1), null, "US", "policy", TENANCY_ID, null);
        agentRegistry.register(descriptor);

        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(cap2), null, "US", "policy", null, List.of());

        assertEquals(NodeStatus.DRIFTED, checker.check(spec, TENANCY_ID));
    }

    @Test
    void unknownSpecType() {
        var spec = new io.casehub.ops.api.deployment.ChannelNodeSpec(
                "ch1", "desc", io.casehub.qhorus.api.channel.ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(NodeStatus.UNKNOWN, checker.check(spec, TENANCY_ID));
    }

    // Test stub
    static class StubAgentRegistry implements AgentRegistry {
        private final Map<String, AgentDescriptor> agents = new ConcurrentHashMap<>();

        @Override
        public void register(AgentDescriptor descriptor) {
            String key = descriptor.agentId() + ":" + descriptor.tenancyId();
            agents.put(key, descriptor);
        }

        @Override
        public Optional<AgentDescriptor> findById(String agentId, String tenancyId) {
            String key = agentId + ":" + tenancyId;
            return Optional.ofNullable(agents.get(key));
        }

        @Override
        public List<AgentDescriptor> find(AgentQuery query) {
            return new ArrayList<>(agents.values());
        }
    }
}
