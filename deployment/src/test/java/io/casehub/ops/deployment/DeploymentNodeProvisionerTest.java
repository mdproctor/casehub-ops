package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.eidos.api.*;
import io.casehub.ops.api.deployment.*;
import io.casehub.ops.deployment.handler.*;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentNodeProvisionerTest {

    private DeploymentNodeProvisioner provisioner;
    private StubAgentRegistry agentRegistry;
    private StubChannelOperations channelOps;
    private SpecHashStore specHashStore;
    private DesiredStateGraph emptyGraph;

    @BeforeEach
    void setUp() {
        agentRegistry = new StubAgentRegistry();
        channelOps = new StubChannelOperations();
        specHashStore = new SpecHashStore();
        var providerConfigStore = new DeploymentProviderConfigStore();
        var caseTypeHandler = new CaseTypeProvisionHandler();
        var trustProvider = new DeploymentTrustRoutingPolicyProvider();
        var trustHandler = new TrustPolicyProvisionHandler(trustProvider);
        emptyGraph = new DefaultDesiredStateGraphFactory().empty();

        provisioner = new DeploymentNodeProvisioner(
                agentRegistry,
                providerConfigStore,
                new ChannelProvisionHandler(channelOps),
                caseTypeHandler,
                trustHandler,
                specHashStore);
    }

    @Test
    void dispatchesAgentToHandler() {
        var cap = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        var disp = AgentDisposition.builder().delegation(false).build();
        var spec = new AgentNodeSpec("agent-1", "Worker Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(cap), disp, "US", "policy", null, List.of());
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var context = new ProvisionContext("tenant-1", emptyGraph);

        var result = provisioner.provision(node, context);

        assertTrue(result instanceof ProvisionResult.Success);
        assertTrue(agentRegistry.descriptors.containsKey("agent-1:tenant-1"));
    }

    @Test
    void dispatchesChannelToHandler() {
        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND), Set.of(), null, null, null, null, null, null, null, null, null);
        var node = new DesiredNode(NodeId.of("ch1"), NodeType.of("channel"), spec, false);
        var context = new ProvisionContext("tenant-1", emptyGraph);

        var result = provisioner.provision(node, context);

        assertTrue(result instanceof ProvisionResult.Success);
        assertTrue(channelOps.channels.containsKey("dev/work"));
    }

    @Test
    void rejectsNonDeploymentNodeSpec() {
        // Use a simple stub spec that's not DeploymentNodeSpec
        NodeSpec unknownSpec = new NodeSpec() {};
        var node = new DesiredNode(NodeId.of("ns1"), NodeType.of("unknown"), unknownSpec, false);
        var context = new ProvisionContext("tenant-1", emptyGraph);

        var result = provisioner.provision(node, context);

        assertTrue(result instanceof ProvisionResult.Failed);
        var failed = (ProvisionResult.Failed) result;
        assertTrue(failed.reason().contains("not DeploymentNodeSpec"));
    }

    @Test
    void provisionRecordsSpecHash() {
        var spec = new AgentNodeSpec("agent-1", "Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", null, null, null, null, null, List.of(), null, null, null, null, List.of());
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        provisioner.provision(node, new ProvisionContext("tenant-1", emptyGraph));
        assertThat(specHashStore.hasDrifted(NodeId.of("a1"), spec)).isFalse();
    }

    @Test
    void deprovisionRemovesSpecHash() {
        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);
        var node = new DesiredNode(NodeId.of("ch1"), NodeType.of("channel"), spec, false);
        provisioner.provision(node, new ProvisionContext("tenant-1", emptyGraph));
        assertThat(specHashStore.hasDrifted(NodeId.of("ch1"), spec)).isFalse();
        provisioner.deprovision(node, new DeprovisionContext("tenant-1", emptyGraph));
        assertThat(specHashStore.hasDrifted(NodeId.of("ch1"), spec)).isTrue();
    }

    @Test
    void failedProvisionDoesNotRecordHash() {
        NodeSpec unknownSpec = new NodeSpec() {};
        var node = new DesiredNode(NodeId.of("ns1"), NodeType.of("unknown"), unknownSpec, false);
        provisioner.provision(node, new ProvisionContext("tenant-1", emptyGraph));
        // Unknown spec causes Failed — should not record hash
        assertThat(specHashStore.hasDrifted(NodeId.of("ns1"), unknownSpec)).isTrue();
    }

    // Test stubs
    static class StubAgentRegistry implements AgentRegistry {
        final Map<String, AgentDescriptor> descriptors = new ConcurrentHashMap<>();

        @Override
        public void register(AgentDescriptor descriptor) {
            String key = descriptor.agentId() + ":" + descriptor.tenancyId();
            descriptors.put(key, descriptor);
        }

        @Override
        public Optional<AgentDescriptor> findById(String agentId, String tenancyId) {
            String key = agentId + ":" + tenancyId;
            return Optional.ofNullable(descriptors.get(key));
        }

        @Override
        public List<AgentDescriptor> find(AgentQuery query) {
            return new ArrayList<>(descriptors.values());
        }
    }

    static class StubChannelOperations implements ChannelProvisionHandler.ChannelOperations {
        final Map<String, Channel> channels = new ConcurrentHashMap<>();

        @Override
        public Optional<Channel> findByName(String name) {
            return Optional.ofNullable(channels.get(name));
        }

        @Override
        public Channel create(ChannelCreateRequest req) {
            Channel ch = new Channel();
            ch.id = UUID.randomUUID();
            ch.name = req.name();
            ch.semantic = req.semantic();
            channels.put(ch.name, ch);
            return ch;
        }

        @Override
        public void delete(UUID channelId, boolean force) {
            channels.values().removeIf(ch -> ch.id.equals(channelId));
        }

        @Override
        public Channel setTypeConstraints(UUID channelId, Set<MessageType> allowed, Set<MessageType> denied) {
            return channels.values().stream().filter(ch -> ch.id.equals(channelId)).findFirst().orElse(null);
        }

        @Override
        public Channel setRateLimits(UUID channelId, Integer perChannel, Integer perInstance) {
            return channels.values().stream().filter(ch -> ch.id.equals(channelId)).findFirst().orElse(null);
        }

        @Override
        public Channel setAllowedWriters(UUID channelId, String allowedWriters) {
            return channels.values().stream().filter(ch -> ch.id.equals(channelId)).findFirst().orElse(null);
        }

        @Override
        public Channel setAdminInstances(UUID channelId, String adminInstances) {
            return channels.values().stream().filter(ch -> ch.id.equals(channelId)).findFirst().orElse(null);
        }
    }
}
