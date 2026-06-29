package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.eidos.api.*;
import io.casehub.ops.api.deployment.*;
import io.casehub.ops.deployment.drift.*;
import io.casehub.ops.deployment.handler.*;
import io.casehub.platform.api.endpoints.*;
import io.casehub.platform.api.path.Path;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelConnectorBinding;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.casehub.qhorus.runtime.store.CrossTenantChannelStore;
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
    private StubChannelStore channelStore;
    private StubChannelBindingStore bindingStore;
    private StubEndpointRegistry endpointRegistry;
    private CaseTypeProvisionHandler caseTypeHandler;
    private DeploymentTrustRoutingPolicyProvider trustProvider;
    private SpecHashStore specHashStore;
    private DeploymentProviderConfigStore providerConfigStore;

    private static final String TENANCY_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        // Create stubs
        agentRegistry = new StubAgentRegistry();
        channelStore = new StubChannelStore();
        bindingStore = new StubChannelBindingStore();
        channelOps = new StubChannelOperations(channelStore);
        endpointRegistry = new StubEndpointRegistry();
        providerConfigStore = new DeploymentProviderConfigStore();
        caseTypeHandler = new CaseTypeProvisionHandler();
        trustProvider = new DeploymentTrustRoutingPolicyProvider();

        // Create shared spec hash store
        specHashStore = new SpecHashStore();

        // Create drift checkers that use the stubs
        var agentChecker = new AgentDriftChecker(agentRegistry);
        var channelChecker = new ChannelDriftChecker(channelStore, bindingStore);
        var caseTypeChecker = new CaseTypeDriftChecker(caseTypeHandler);
        var trustChecker = new TrustPolicyDriftChecker(trustProvider);
        var endpointChecker = new EndpointDriftChecker(endpointRegistry);

        // Wire everything
        compiler = new DeploymentGoalCompiler();
        provisioner = new DeploymentNodeProvisioner(
                agentRegistry,
                providerConfigStore,
                new ChannelProvisionHandler(channelOps),
                caseTypeHandler,
                new TrustPolicyProvisionHandler(trustProvider),
                new EndpointProvisionHandler(endpointRegistry),
                specHashStore);
        adapter = new DeploymentActualStateAdapter(
                List.of(agentChecker, channelChecker, caseTypeChecker, trustChecker, endpointChecker),
                specHashStore);
        eventSource = new DeploymentEventSource();
        faultPolicy = new DeploymentFaultPolicy();
        graphFactory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void fullLifecycle_declare_compile_provision_readState() {
        // Declare 5 nodes (one of each type including endpoint)
        var agentCap = new AgentCapability("cap-a", null, null, null, List.of(), List.of(), List.of(), Map.of(), null);
        var agentDisp = AgentDisposition.builder().delegation(false).build();
        var claudonyConfig = new ProviderConfig("claudony", Map.of("tools", "read,write"));
        var agentSpec = new AgentNodeSpec("agent-1", "Worker Agent", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(agentCap), agentDisp, "US", "policy", "Reviews code quality", List.of(claudonyConfig));

        var channelSpec = new ChannelNodeSpec("dev/work", "desc", ChannelSemantic.APPEND,
                Set.of(MessageType.COMMAND), Set.of(), null, null, null, null, null, null, null, null, null);

        var caseTypeSpec = new CaseTypeNodeSpec("io.casehub.devtown", "pr-review", "1.0", "PR Review", "Automated", "test-case-defs/pr-review.yaml", null);

        var trustSpec = new TrustPolicyNodeSpec("cap-a", 0.8, 5, 0.1, 0.5, Map.of(), false);

        var endpointSpec = new EndpointNodeSpec(
                "test/kafka-stream",
                EndpointType.SERVICE,
                EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, "test.events"),
                null,
                Set.of(EndpointCapability.RECEIVE));

        var deploymentGoals = new DeploymentGoals(
                List.of(new GoalEntry<>(agentSpec, List.of("test/kafka-stream"))),
                List.of(new GoalEntry<>(channelSpec, List.of())),
                List.of(new GoalEntry<>(caseTypeSpec, List.of())),
                List.of(new GoalEntry<>(trustSpec, List.of())),
                List.of(new GoalEntry<>(endpointSpec, List.of())),
                List.of());

        // Compile
        var desired = compiler.compile(deploymentGoals, graphFactory);
        assertThat(desired.nodes()).hasSize(5);
        assertThat(desired.dependencies()).hasSize(1);

        // Verify cross-type dependency: agent → endpoint
        var dep = desired.dependencies().iterator().next();
        assertThat(dep.from()).isEqualTo(NodeId.of("agent-1"));
        assertThat(dep.to()).isEqualTo(NodeId.of("test/kafka-stream"));

        // Provision all
        var provisionContext = new ProvisionContext(TENANCY_ID, desired);
        for (var node : desired.nodes().values()) {
            var result = provisioner.provision(node, provisionContext);
            assertThat(result)
                    .as("provisioning %s", node.id())
                    .isInstanceOf(ProvisionResult.Success.class);
        }

        // Read actual state
        var actual = adapter.readActual(desired, TENANCY_ID);
        assertThat(actual.statuses()).hasSize(5);
        for (var status : actual.statuses().values()) {
            assertThat(status).isEqualTo(NodeStatus.PRESENT);
        }

        // Verify provider configs stored
        assertThat(providerConfigStore.forAgent("agent-1")).hasSize(1);
        var storedConfig = providerConfigStore.forAgent("agent-1").get(0);
        assertThat(storedConfig.providerName()).isEqualTo("claudony");
        assertThat(storedConfig.config().get("tools")).isEqualTo("read,write");

        // Verify case type definition payload resolved
        var caseTypeNode = desired.nodes().values().stream()
                .filter(n -> n.spec() instanceof CaseTypeNodeSpec)
                .findFirst()
                .orElseThrow();
        var resolvedSpec = (CaseTypeNodeSpec) caseTypeNode.spec();
        assertThat(resolvedSpec.definitionPayload()).isNotNull();
        assertThat(resolvedSpec.definitionPayload().get("namespace")).isEqualTo("io.casehub.devtown");
        assertThat(resolvedSpec.definitionPayload().get("name")).isEqualTo("pr-review");
    }

    @Test
    void driftDetection_specHashChangeReportsDrifted() {
        // Create and provision an agent
        var agentCap = new AgentCapability("cap-b", null, null, null, List.of(), List.of(), List.of(), Map.of(), null);
        var agentDisp = AgentDisposition.builder().delegation(false).build();
        var agentSpec = new AgentNodeSpec("agent-drift", "Original", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(agentCap), agentDisp, "US", "policy", null, List.of());

        var deploymentGoals = new DeploymentGoals(
                List.of(new GoalEntry<>(agentSpec, List.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        var desired = compiler.compile(deploymentGoals, graphFactory);
        var provisionContext = new ProvisionContext(TENANCY_ID, desired);
        var node = desired.nodes().values().iterator().next();
        var result = provisioner.provision(node, provisionContext);
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);

        // Compile a modified agent (different name field = different spec hash)
        var modifiedSpec = new AgentNodeSpec("agent-drift", "Modified Name", "worker", "anthropic", "claude", "4.6",
                "1.0", "fp1", "domain", "slot", "disp", Map.of(), List.of(agentCap), agentDisp, "US", "policy", null, List.of());
        var modifiedGoals = new DeploymentGoals(
                List.of(new GoalEntry<>(modifiedSpec, List.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        var modifiedDesired = compiler.compile(modifiedGoals, graphFactory);

        // Read actual state — should detect drift
        var actual = adapter.readActual(modifiedDesired, TENANCY_ID);
        var status = actual.statuses().get(NodeId.of("agent-drift"));
        assertThat(status).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void driftDetection_endpointPropertyChangeReportsDrifted() {
        // Create and provision a KAFKA endpoint
        var endpointSpec = new EndpointNodeSpec(
                "test/kafka-drift",
                EndpointType.SERVICE,
                EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, "original.topic"),
                null,
                Set.of(EndpointCapability.RECEIVE));

        var deploymentGoals = new DeploymentGoals(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new GoalEntry<>(endpointSpec, List.of())),
                List.of());

        var desired = compiler.compile(deploymentGoals, graphFactory);
        var provisionContext = new ProvisionContext(TENANCY_ID, desired);
        var node = desired.nodes().values().iterator().next();
        var result = provisioner.provision(node, provisionContext);
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);

        // Compile a modified endpoint (different topic = different properties)
        var modifiedSpec = new EndpointNodeSpec(
                "test/kafka-drift",
                EndpointType.SERVICE,
                EndpointProtocol.KAFKA,
                Map.of(EndpointPropertyKeys.TOPIC, "modified.topic"),
                null,
                Set.of(EndpointCapability.RECEIVE));
        var modifiedGoals = new DeploymentGoals(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new GoalEntry<>(modifiedSpec, List.of())),
                List.of());
        var modifiedDesired = compiler.compile(modifiedGoals, graphFactory);

        // Read actual state — should detect drift
        var actual = adapter.readActual(modifiedDesired, TENANCY_ID);
        var status = actual.statuses().get(NodeId.of("test/kafka-drift"));
        assertThat(status).isEqualTo(NodeStatus.DRIFTED);
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
        final StubChannelStore channelStore;

        StubChannelOperations(StubChannelStore channelStore) {
            this.channelStore = channelStore;
        }

        @Override
        public Optional<Channel> findByName(String name) {
            return Optional.ofNullable(channels.get(name));
        }

        @Override
        public Channel create(ChannelCreateRequest req) {
            Channel ch = new Channel();
            ch.id = UUID.randomUUID();
            ch.name = req.name();
            ch.description = req.description();
            ch.semantic = req.semantic();
            ch.allowedTypes = req.allowedTypes() != null ? MessageType.serializeTypes(req.allowedTypes()) : null;
            ch.deniedTypes = req.deniedTypes() != null ? MessageType.serializeTypes(req.deniedTypes()) : null;
            ch.rateLimitPerChannel = req.rateLimitPerChannel();
            ch.rateLimitPerInstance = req.rateLimitPerInstance();
            ch.allowedWriters = req.allowedWriters();
            ch.adminInstances = req.adminInstances();
            ch.barrierContributors = req.barrierContributors();
            channels.put(ch.name, ch);
            // Also store in channelStore with tenancy — using TENANCY_ID constant
            channelStore.put(ch, TENANCY_ID);
            return ch;
        }

        @Override
        public void delete(UUID channelId, boolean force) {
            channels.values().removeIf(ch -> ch.id.equals(channelId));
            // Also remove from channelStore
            channelStore.channels.entrySet().removeIf(e -> e.getValue().id.equals(channelId));
        }

        @Override
        public Channel setTypeConstraints(UUID channelId, Set<MessageType> allowed, Set<MessageType> denied) {
            for (Channel ch : channels.values()) {
                if (ch.id.equals(channelId)) {
                    ch.allowedTypes = allowed != null ? MessageType.serializeTypes(allowed) : null;
                    ch.deniedTypes = denied != null ? MessageType.serializeTypes(denied) : null;
                    // Update is in-place, no need to update channelStore separately
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
                    // Update is in-place, no need to update channelStore separately
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
                    // Update is in-place, no need to update channelStore separately
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
                    // Update is in-place, no need to update channelStore separately
                    return ch;
                }
            }
            return null;
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

    static class StubChannelStore implements CrossTenantChannelStore {
        final Map<String, Channel> channels = new ConcurrentHashMap<>();

        private String key(String name, String tenancyId) {
            return name + ":" + tenancyId;
        }

        @Override
        public Optional<Channel> findByNameAndTenancy(String name, String tenancyId) {
            return Optional.ofNullable(channels.get(key(name, tenancyId)));
        }

        @Override
        public List<Channel> listAll() {
            return new ArrayList<>(channels.values());
        }

        @Override
        public Optional<Channel> findById(UUID id) {
            return channels.values().stream()
                    .filter(ch -> ch.id.equals(id))
                    .findFirst();
        }

        void put(Channel channel, String tenancyId) {
            channels.put(key(channel.name, tenancyId), channel);
        }
    }

    static class StubChannelBindingStore implements ChannelBindingStore {
        final Map<UUID, ChannelConnectorBinding> bindings = new ConcurrentHashMap<>();

        @Override
        public Optional<ChannelConnectorBinding> findByChannelId(UUID channelId) {
            return Optional.ofNullable(bindings.get(channelId));
        }

        @Override
        public Optional<ChannelConnectorBinding> findByKey(String inboundConnectorId, String externalKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void put(ChannelConnectorBinding binding) {
            bindings.put(binding.channelId, binding);
        }

        @Override
        public void delete(UUID channelId) {
            bindings.remove(channelId);
        }

        @Override
        public Map<UUID, ChannelConnectorBinding> findAll() {
            return new HashMap<>(bindings);
        }
    }
}
