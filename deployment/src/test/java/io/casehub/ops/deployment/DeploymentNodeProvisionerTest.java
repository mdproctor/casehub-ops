package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.eidos.api.*;
import io.casehub.ops.api.approval.*;
import io.casehub.ops.api.deployment.*;
import io.casehub.ops.deployment.handler.*;
import io.casehub.platform.api.endpoints.*;
import io.casehub.platform.api.path.Path;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentNodeProvisionerTest {

    private DeploymentNodeProvisioner provisioner;
    private StubAgentRegistry agentRegistry;
    private StubChannelOperations channelOps;
    private StubEndpointRegistry endpointRegistry;
    private SpecHashStore specHashStore;
    private DesiredStateGraph emptyGraph;
    private InMemoryPlanStore planStore;

    @BeforeEach
    void setUp() {
        agentRegistry = new StubAgentRegistry();
        channelOps = new StubChannelOperations();
        endpointRegistry = new StubEndpointRegistry();
        specHashStore = new SpecHashStore();
        planStore = new InMemoryPlanStore();
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
                new EndpointProvisionHandler(endpointRegistry),
                specHashStore,
                new DeploymentApprovalEvaluator(),
                planStore);
    }

    @Test
    void dispatchesAgentToHandler() {
        var cap = new AgentCapability("cap-a", null, null, null, null, null, List.of(), List.of(), List.of(), Map.of(), null);
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

    // --- Approval flow tests ---

    @Test
    void highRiskNodeReturnsPendingApproval() {
        var spec = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var node = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec, false);
        var context = new ProvisionContext("tenant-1", emptyGraph);

        var result = provisioner.provision(node, context);

        assertThat(result).isInstanceOf(ProvisionResult.PendingApproval.class);
        var pending = (ProvisionResult.PendingApproval) result;
        assertThat(pending.nodeId()).isEqualTo(NodeId.of("tp-1"));
        assertThat(pending.planReference()).isNotNull();
        // Plan should be stored
        assertThat(planStore.retrieve(pending.planReference())).isPresent();
    }

    @Test
    void lowRiskNodeProvisionsDirect() {
        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                null, null, null, null, null, null, null, null, null, null, null);
        var node = new DesiredNode(NodeId.of("ch-1"), NodeType.of("channel"), spec, false);
        var context = new ProvisionContext("tenant-1", emptyGraph);

        var result = provisioner.provision(node, context);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(channelOps.channels.containsKey("dev/work")).isTrue();
    }

    @Test
    void reEntryWithValidApprovalProvisions() {
        var spec = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var node = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec, false);

        // First call — gets PendingApproval
        var firstResult = provisioner.provision(node, new ProvisionContext("tenant-1", emptyGraph));
        assertThat(firstResult).isInstanceOf(ProvisionResult.PendingApproval.class);
        var pending = (ProvisionResult.PendingApproval) firstResult;
        String planRef = pending.planReference();

        // Re-entry with approval
        var approval = new PlanApproval(planRef, "admin", Instant.now());
        var reEntryContext = new ProvisionContext("tenant-1", emptyGraph, approval);

        var result = provisioner.provision(node, reEntryContext);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        // Plan should be removed after successful provision
        assertThat(planStore.retrieve(planRef)).isEmpty();
    }

    @Test
    void reEntryWithStaleSpecReEvaluates() {
        var originalSpec = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var originalNode = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), originalSpec, false);

        // First call — gets PendingApproval
        var firstResult = provisioner.provision(originalNode, new ProvisionContext("tenant-1", emptyGraph));
        assertThat(firstResult).isInstanceOf(ProvisionResult.PendingApproval.class);
        var pending = (ProvisionResult.PendingApproval) firstResult;
        String originalRef = pending.planReference();

        // Spec changed between approval and re-entry
        var changedSpec = new TrustPolicyNodeSpec("claims-routing", 0.95, 10, 0.1, 0.3, Map.of(), false);
        var changedNode = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), changedSpec, false);

        var approval = new PlanApproval(originalRef, "admin", Instant.now());
        var reEntryContext = new ProvisionContext("tenant-1", emptyGraph, approval);

        var result = provisioner.provision(changedNode, reEntryContext);

        // Should get a new PendingApproval since spec changed and TrustPolicy is still HIGH risk
        assertThat(result).isInstanceOf(ProvisionResult.PendingApproval.class);
        var newPending = (ProvisionResult.PendingApproval) result;
        // Original plan should be removed
        assertThat(planStore.retrieve(originalRef)).isEmpty();
        // New plan should be stored
        assertThat(newPending.planReference()).isNotEqualTo(originalRef);
        assertThat(planStore.retrieve(newPending.planReference())).isPresent();
    }

    @Test
    void deprovisionApprovalFlow() {
        var spec = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var node = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec, false);

        // Deprovision — should require approval
        var deprovResult = provisioner.deprovision(node, new DeprovisionContext("tenant-1", emptyGraph));
        assertThat(deprovResult).isInstanceOf(DeprovisionResult.PendingApproval.class);
        var pending = (DeprovisionResult.PendingApproval) deprovResult;
        String planRef = pending.planReference();
        assertThat(planStore.retrieve(planRef)).isPresent();

        // Re-entry with approval
        var approval = new PlanApproval(planRef, "admin", Instant.now());
        var reEntryContext = new DeprovisionContext("tenant-1", emptyGraph, approval);

        var result = provisioner.deprovision(node, reEntryContext);

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(planStore.retrieve(planRef)).isEmpty();
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
        public List<AgentMatch> find(AgentQuery query) {
            return descriptors.values().stream()
                    .map(d -> new AgentMatch(d, null))
                    .collect(java.util.stream.Collectors.toList());
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
            Channel ch = Channel.builder(req.name())
                    .id(UUID.randomUUID())
                    .semantic(req.semantic())
                    .build();
            channels.put(ch.name(), ch);
            return ch;
        }

        @Override
        public void delete(UUID channelId, boolean force) {
            channels.values().removeIf(ch -> ch.id().equals(channelId));
        }

        @Override
        public Channel setTypeConstraints(UUID channelId, Set<MessageType> allowed, Set<MessageType> denied) {
            return channels.values().stream().filter(ch -> ch.id().equals(channelId)).findFirst().orElse(null);
        }

        @Override
        public Channel setRateLimits(UUID channelId, Integer perChannel, Integer perInstance) {
            return channels.values().stream().filter(ch -> ch.id().equals(channelId)).findFirst().orElse(null);
        }

        @Override
        public Channel setAllowedWriters(UUID channelId, List<String> allowedWriters) {
            return channels.values().stream().filter(ch -> ch.id().equals(channelId)).findFirst().orElse(null);
        }

        @Override
        public Channel setAdminInstances(UUID channelId, List<String> adminInstances) {
            return channels.values().stream().filter(ch -> ch.id().equals(channelId)).findFirst().orElse(null);
        }
    }

    static class StubEndpointRegistry implements EndpointRegistry {
        private final Map<String, EndpointDescriptor> endpoints = new ConcurrentHashMap<>();

        private String key(Path path, String tenancyId) {
            return path.value() + ":" + tenancyId;
        }

        @Override
        public void register(EndpointDescriptor endpoint) {
            endpoints.put(key(endpoint.path(), endpoint.tenancyId()), endpoint);
        }

        @Override
        public Optional<EndpointDescriptor> resolve(Path path, String tenancyId) {
            return Optional.ofNullable(endpoints.get(key(path, tenancyId)));
        }

        @Override
        public List<EndpointDescriptor> discover(EndpointQuery query) {
            return new ArrayList<>(endpoints.values());
        }

        @Override
        public void deregister(Path path, String tenancyId) {
            endpoints.remove(key(path, tenancyId));
        }
    }
}
