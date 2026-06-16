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
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentLifecycleIntegrationTest {

    private DeploymentGoalCompiler compiler;
    private DeploymentNodeProvisioner provisioner;
    private DeploymentActualStateAdapter adapter;
    private DeploymentEventSource eventSource;
    private DeploymentFaultPolicy faultPolicy;
    private DefaultDesiredStateGraphFactory graphFactory;

    private StubAgentRegistry agentRegistry;
    private StubChannelOperations channelOps;
    private CaseTypeProvisionHandler caseTypeHandler;
    private DeploymentTrustRoutingPolicyProvider trustProvider;

    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        // Create stubs
        agentRegistry = new StubAgentRegistry();
        channelOps = new StubChannelOperations();
        caseTypeHandler = new CaseTypeProvisionHandler();
        trustProvider = new DeploymentTrustRoutingPolicyProvider();

        // Wire everything
        compiler = new DeploymentGoalCompiler();
        provisioner = new DeploymentNodeProvisioner(
                new AgentProvisionHandler(agentRegistry),
                new ChannelProvisionHandler(channelOps),
                caseTypeHandler,
                new TrustPolicyProvisionHandler(trustProvider));
        adapter = new DeploymentActualStateAdapter(
                agentRegistry,
                channelOps::findByName,
                caseTypeHandler,
                trustProvider,
                TENANCY_ID);
        eventSource = new DeploymentEventSource();
        faultPolicy = new DeploymentFaultPolicy();
        graphFactory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void fullLifecycle_declare_compile_provision_readState() {
        // Declare 4 nodes (one of each type)
        var agentCap = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of());
        var agentDisp = AgentDisposition.builder().delegation(false).build();
        var agentSpec = new AgentNodeSpec("agent-1", "Worker Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(agentCap), agentDisp, "US", "policy");

        var channelSpec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND), Set.of(), null, null, null, null, null, null, null, null, null);

        var caseTypeSpec = new CaseTypeNodeSpec("ns", "Incident", "1.0", "Incident Case", "summary");

        var trustSpec = new TrustPolicyNodeSpec("cap-a", 0.8, 5, 0.1, 0.5, Map.of(), false);

        var deploymentGoals = new DeploymentGoals(
                List.of(new GoalEntry<>(agentSpec, List.of())),
                List.of(new GoalEntry<>(channelSpec, List.of())),
                List.of(new GoalEntry<>(caseTypeSpec, List.of())),
                List.of(new GoalEntry<>(trustSpec, List.of())));

        // Compile
        var desired = compiler.compile(deploymentGoals, graphFactory);
        assertThat(desired.nodes()).hasSize(4);

        // Provision all
        var provisionContext = new ProvisionContext(TENANCY_ID, desired);
        for (var node : desired.nodes().values()) {
            var result = provisioner.provision(node, provisionContext);
            assertThat(result)
                    .as("provisioning %s", node.id())
                    .isInstanceOf(ProvisionResult.Success.class);
        }

        // Read actual state
        var actual = adapter.readActual(desired);
        assertThat(actual.statuses()).hasSize(4);
        for (var status : actual.statuses().values()) {
            assertThat(status).isEqualTo(NodeStatus.PRESENT);
        }
    }

    @Test
    void eventSource_emitDrift() {
        var subscriber = eventSource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        eventSource.emitDrift(NodeId.of("node-1"));

        var items = subscriber.getItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).node()).isEqualTo(NodeId.of("node-1"));
        assertThat(items.get(0).newStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void faultPolicy_returnsNoMutations() {
        var graph = graphFactory.empty();
        var event = new FaultEvent(NodeId.of("node-1"), FaultType.PROVISION_FAILED, "test error");

        var mutations = faultPolicy.onFault(event, graph);

        assertThat(mutations).isEmpty();
    }

    // Test stubs
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
            ch.allowedTypes = req.allowedTypes() != null ? MessageType.serializeTypes(req.allowedTypes()) : null;
            ch.deniedTypes = req.deniedTypes() != null ? MessageType.serializeTypes(req.deniedTypes()) : null;
            ch.rateLimitPerChannel = req.rateLimitPerChannel();
            ch.rateLimitPerInstance = req.rateLimitPerInstance();
            channels.put(ch.name, ch);
            return ch;
        }

        @Override
        public void delete(UUID channelId, boolean force) {
            channels.values().removeIf(ch -> ch.id.equals(channelId));
        }

        @Override
        public Channel setTypeConstraints(UUID channelId, Set<MessageType> allowed, Set<MessageType> denied) {
            for (Channel ch : channels.values()) {
                if (ch.id.equals(channelId)) {
                    ch.allowedTypes = allowed != null ? MessageType.serializeTypes(allowed) : null;
                    ch.deniedTypes = denied != null ? MessageType.serializeTypes(denied) : null;
                    return ch;
                }
            }
            return null;
        }

        @Override
        public Channel setRateLimits(UUID channelId, Integer perChannel, Integer perInstance) {
            for (Channel ch : channels.values()) {
                if (ch.id.equals(channelId)) {
                    ch.rateLimitPerChannel = perChannel;
                    ch.rateLimitPerInstance = perInstance;
                    return ch;
                }
            }
            return null;
        }

        @Override
        public Channel setAllowedWriters(UUID channelId, String allowedWriters) {
            for (Channel ch : channels.values()) {
                if (ch.id.equals(channelId)) {
                    ch.allowedWriters = allowedWriters;
                    return ch;
                }
            }
            return null;
        }

        @Override
        public Channel setAdminInstances(UUID channelId, String adminInstances) {
            for (Channel ch : channels.values()) {
                if (ch.id.equals(channelId)) {
                    ch.adminInstances = adminInstances;
                    return ch;
                }
            }
            return null;
        }
    }
}
