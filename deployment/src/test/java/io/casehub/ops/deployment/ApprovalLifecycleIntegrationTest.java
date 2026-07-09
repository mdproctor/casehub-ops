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

/**
 * Integration test exercising the complete approval lifecycle through a real provisioner
 * with real evaluator, handler, and plan store.
 *
 * Scenarios:
 * 1. High-risk node requires approval → human approves → provisions successfully
 * 2. Low-risk node auto-approves and provisions directly
 * 3. Rejection flow cleans up plan on acknowledge
 * 4. Stale approval (spec changed since approval) triggers re-evaluation
 * 5. Deprovision approval flow
 */
class ApprovalLifecycleIntegrationTest {

    private DeploymentNodeProvisioner provisioner;
    private OpsPendingApprovalHandler handler;
    private InMemoryPlanStore planStore;
    private DesiredStateGraph emptyGraph;

    @BeforeEach
    void setUp() {
        planStore = new InMemoryPlanStore();
        handler = new OpsPendingApprovalHandler(planStore);
        var evaluator = new DeploymentApprovalEvaluator();
        var agentRegistry = new StubAgentRegistry();
        var providerConfigStore = new DeploymentProviderConfigStore();
        var trustProvider = new DeploymentTrustRoutingPolicyProvider();
        emptyGraph = new DefaultDesiredStateGraphFactory().empty();

        provisioner = new DeploymentNodeProvisioner(
                agentRegistry,
                providerConfigStore,
                new ChannelProvisionHandler(new StubChannelOperations()),
                new CaseTypeProvisionHandler(),
                new TrustPolicyProvisionHandler(trustProvider),
                new EndpointProvisionHandler(new StubEndpointRegistry()),
                new SpecHashStore(),
                evaluator,
                planStore);
    }

    @Test
    void happyPath_highRiskNodeRequiresApproval_thenProvisions() {
        var spec = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var node = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec, false);
        var context = new ProvisionContext("tenant-1", emptyGraph);

        // Cycle 1: provisioner returns PendingApproval
        var result1 = provisioner.provision(node, context);
        assertThat(result1).isInstanceOf(ProvisionResult.PendingApproval.class);
        var pa = (ProvisionResult.PendingApproval) result1;

        // Simulate executor calling recordPending
        handler.recordPending(node, StepAction.PROVISION, "tenant-1", pa.planReference());

        // Human approves
        handler.approve(NodeId.of("tp-1"), StepAction.PROVISION, "tenant-1", "admin");

        // Cycle 2: executor sees Approved, enriches context
        var check = handler.check(node, StepAction.PROVISION, "tenant-1");
        assertThat(check).isInstanceOf(ApprovalCheckResult.Approved.class);
        var approved = (ApprovalCheckResult.Approved) check;
        var contextWithApproval = context.withApproval(approved.approval());

        // Provisioner re-entry: provisions successfully
        var result2 = provisioner.provision(node, contextWithApproval);
        assertThat(result2).isInstanceOf(ProvisionResult.Success.class);

        // Plan cleaned up
        assertThat(planStore.retrieve(pa.planReference())).isEmpty();
    }

    @Test
    void autoApprove_lowRiskNodeProvisionsDirect() {
        var spec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND), Set.of(), null, null, null, null, null, null, null, null, null);
        var node = new DesiredNode(NodeId.of("ch-1"), NodeType.of("channel"), spec, false);
        var context = new ProvisionContext("tenant-1", emptyGraph);

        var result = provisioner.provision(node, context);
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void rejection_cleansUpPlanOnAcknowledge() {
        var spec = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var node = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec, false);
        var context = new ProvisionContext("tenant-1", emptyGraph);

        var result = provisioner.provision(node, context);
        var pa = (ProvisionResult.PendingApproval) result;
        handler.recordPending(node, StepAction.PROVISION, "tenant-1", pa.planReference());

        // Reject
        handler.reject(NodeId.of("tp-1"), StepAction.PROVISION, "tenant-1", "too risky");

        // Acknowledge
        handler.acknowledgeRejection(node, StepAction.PROVISION, "tenant-1");

        // State is clean
        assertThat(handler.check(node, StepAction.PROVISION, "tenant-1"))
                .isInstanceOf(ApprovalCheckResult.None.class);
        assertThat(planStore.retrieve(pa.planReference())).isEmpty();
    }

    @Test
    void staleApproval_specChangedSinceApproval_reEvaluates() {
        var spec1 = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var node1 = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec1, false);
        var context = new ProvisionContext("tenant-1", emptyGraph);

        // Cycle 1: PendingApproval for spec1
        var result1 = provisioner.provision(node1, context);
        var pa1 = (ProvisionResult.PendingApproval) result1;
        handler.recordPending(node1, StepAction.PROVISION, "tenant-1", pa1.planReference());
        handler.approve(NodeId.of("tp-1"), StepAction.PROVISION, "tenant-1", "admin");

        // Spec changes before re-entry (different confidence threshold)
        var spec2 = new TrustPolicyNodeSpec("claims-routing", 0.95, 10, 0.1, 0.3, Map.of(), false);
        var node2 = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec2, false);

        var check = handler.check(node2, StepAction.PROVISION, "tenant-1");
        var contextWithApproval = context.withApproval(((ApprovalCheckResult.Approved) check).approval());

        // Provisioner detects stale spec → returns new PendingApproval
        var result2 = provisioner.provision(node2, contextWithApproval);
        assertThat(result2).isInstanceOf(ProvisionResult.PendingApproval.class);

        // Old plan cleaned up
        assertThat(planStore.retrieve(pa1.planReference())).isEmpty();
    }

    @Test
    void deprovisionApprovalFlow() {
        var spec = new TrustPolicyNodeSpec("claims-routing", 0.85, 10, 0.1, 0.3, Map.of(), false);
        var node = new DesiredNode(NodeId.of("tp-1"), NodeType.of("trust"), spec, false);
        var context = new DeprovisionContext("tenant-1", emptyGraph);

        // Cycle 1: deprovision returns PendingApproval
        var result1 = provisioner.deprovision(node, context);
        assertThat(result1).isInstanceOf(DeprovisionResult.PendingApproval.class);
        var pa = (DeprovisionResult.PendingApproval) result1;

        // Simulate executor calling recordPending
        handler.recordPending(node, StepAction.DEPROVISION, "tenant-1", pa.planReference());

        // Human approves
        handler.approve(NodeId.of("tp-1"), StepAction.DEPROVISION, "tenant-1", "admin");

        // Cycle 2: executor sees Approved, enriches context
        var check = handler.check(node, StepAction.DEPROVISION, "tenant-1");
        assertThat(check).isInstanceOf(ApprovalCheckResult.Approved.class);
        var approved = (ApprovalCheckResult.Approved) check;
        var approval = new PlanApproval(pa.planReference(), "admin", Instant.now());
        var contextWithApproval = context.withApproval(approval);

        // Deprovision re-entry: succeeds
        var result2 = provisioner.deprovision(node, contextWithApproval);
        assertThat(result2).isInstanceOf(DeprovisionResult.Success.class);

        // Plan cleaned up
        assertThat(planStore.retrieve(pa.planReference())).isEmpty();
    }

    // ========================================
    // Test Stubs (copied from DeploymentNodeProvisionerTest)
    // ========================================

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
