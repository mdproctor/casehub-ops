package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.eidos.api.*;
import io.casehub.ops.api.deployment.*;
import io.casehub.ops.deployment.handler.CaseTypeProvisionHandler;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentActualStateAdapterTest {

    private DeploymentActualStateAdapter adapter;
    private StubAgentRegistry agentRegistry;
    private Map<String, Channel> channels;
    private DefaultDesiredStateGraphFactory graphFactory;
    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        agentRegistry = new StubAgentRegistry();
        channels = new ConcurrentHashMap<>();
        graphFactory = new DefaultDesiredStateGraphFactory();
        var caseTypeHandler = new CaseTypeProvisionHandler();
        var trustProvider = new DeploymentTrustRoutingPolicyProvider();

        adapter = new DeploymentActualStateAdapter(
                agentRegistry,
                name -> Optional.ofNullable(channels.get(name)),
                caseTypeHandler,
                trustProvider,
                TENANCY_ID);
    }

    @Test
    void agentPresent() {
        var cap = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        var descriptor = new AgentDescriptor(
                "agent-1", "Agent", "anthropic", "claude", "4.6", "1.0", "fp1",
                "domain", "slot", "disp", Map.of(), "worker",
                List.of(cap), null, "US", "policy", TENANCY_ID);
        agentRegistry.register(descriptor);

        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(cap), null, "US", "policy");
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var actual = adapter.readActual(graph);

        assertEquals(NodeStatus.PRESENT, actual.statuses().get(NodeId.of("a1")));
    }

    @Test
    void agentAbsent() {
        var cap = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(cap), null, "US", "policy");
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var actual = adapter.readActual(graph);

        assertEquals(NodeStatus.ABSENT, actual.statuses().get(NodeId.of("a1")));
    }

    @Test
    void agentDrifted_capabilitiesMismatch() {
        var cap1 = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        var cap2 = new AgentCapability("cap-b", null, null, null, List.of(), List.of(), List.of(), Map.of());

        var descriptor = new AgentDescriptor(
                "agent-1", "Agent", "anthropic", "claude", "4.6", "1.0", "fp1",
                "domain", "slot", "disp", Map.of(), "worker",
                List.of(cap1), null, "US", "policy", TENANCY_ID);
        agentRegistry.register(descriptor);

        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(cap2), null, "US", "policy");
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var actual = adapter.readActual(graph);

        assertEquals(NodeStatus.DRIFTED, actual.statuses().get(NodeId.of("a1")));
    }

    @Test
    void channelPresent() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.allowedTypes = MessageType.serializeTypes(Set.of(MessageType.COMMAND));
        ch.deniedTypes = null;
        ch.rateLimitPerChannel = null;
        ch.rateLimitPerInstance = null;
        channels.put("dev/work", ch);

        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND), Set.of(), null, null, null, null, null, null, null, null, null);
        var node = new DesiredNode(NodeId.of("ch1"), NodeType.of("channel"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var actual = adapter.readActual(graph);

        assertEquals(NodeStatus.PRESENT, actual.statuses().get(NodeId.of("ch1")));
    }

    @Test
    void channelDrifted_allowedTypesMismatch() {
        Channel ch = new Channel();
        ch.id = UUID.randomUUID();
        ch.name = "dev/work";
        ch.semantic = ChannelSemantic.APPEND;
        ch.allowedTypes = MessageType.serializeTypes(Set.of(MessageType.COMMAND));
        ch.deniedTypes = null;
        ch.rateLimitPerChannel = null;
        ch.rateLimitPerInstance = null;
        channels.put("dev/work", ch);

        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.EVENT), Set.of(), null, null, null, null, null, null, null, null, null);
        var node = new DesiredNode(NodeId.of("ch1"), NodeType.of("channel"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());

        var actual = adapter.readActual(graph);

        assertEquals(NodeStatus.DRIFTED, actual.statuses().get(NodeId.of("ch1")));
    }

    @Test
    void caseTypeAndTrustAlwaysPresent() {
        var caseTypeSpec = new CaseTypeNodeSpec("ns", "Incident", "1.0", "Incident Case", "summary");
        var caseTypeNode = new DesiredNode(NodeId.of("ct1"), NodeType.of("case_type"), caseTypeSpec, false);

        var trustSpec = new TrustPolicyNodeSpec("cap-a", 0.8, 5, 0.1, 0.5, Map.of(), false);
        var trustNode = new DesiredNode(NodeId.of("tp1"), NodeType.of("trust_policy"), trustSpec, false);

        var graph = graphFactory.of(List.of(caseTypeNode, trustNode), List.of());

        var actual = adapter.readActual(graph);

        assertEquals(NodeStatus.PRESENT, actual.statuses().get(NodeId.of("ct1")));
        assertEquals(NodeStatus.PRESENT, actual.statuses().get(NodeId.of("tp1")));
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
