package io.casehub.ops.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.infra.goal.InfraGoals;
import io.casehub.ops.api.infra.goal.ResourceDeclaration;
import io.casehub.ops.api.infra.spi.InfraBackend;
import io.casehub.ops.api.infra.spi.ResourceProvisioner;
import io.casehub.ops.infra.standalone.InMemoryResourceProvisioner;
import io.casehub.ops.infra.standalone.StandaloneBackend;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;

/**
 * Full lifecycle integration test for the infra module.
 *
 * <p>Validates the complete flow: DECLARE -> COMPILE -> PROVISION -> READ STATE -> DETECT DRIFT.
 * Uses {@link StandaloneBackend} + {@link InMemoryResourceProvisioner} -- no external dependencies.
 */
class InfraLifecycleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DesiredStateGraphFactory GRAPH_FACTORY = new DefaultDesiredStateGraphFactory();

    private InMemoryResourceProvisioner provisioner;
    private StandaloneBackend standaloneBackend;
    private InfraGoalCompiler compiler;
    private InfraNodeProvisioner nodeProvisioner;
    private InfraActualStateAdapter stateAdapter;

    @BeforeEach
    void setUp() {
        provisioner = new InMemoryResourceProvisioner();
        standaloneBackend = new StandaloneBackend(List.<ResourceProvisioner>of(provisioner));
        var backends = List.<InfraBackend>of(standaloneBackend);
        compiler = new InfraGoalCompiler();
        nodeProvisioner = new InfraNodeProvisioner(backends);
        stateAdapter = new InfraActualStateAdapter(backends);
    }

    // --- config helpers ---

    private ObjectNode nsConfig(String name) {
        return MAPPER.createObjectNode().put("name", name);
    }

    private ObjectNode deploymentConfig(String namespace, String name, String image) {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("namespace", namespace);
        config.put("name", name);
        config.put("image", image);
        config.put("replicas", 1);
        ObjectNode resources = config.putObject("resources");
        resources.put("cpuRequest", "100m");
        resources.put("cpuLimit", "500m");
        resources.put("memoryRequest", "128Mi");
        resources.put("memoryLimit", "512Mi");
        return config;
    }

    private ObjectNode serviceConfig(String namespace, String name) {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("namespace", namespace);
        config.put("name", name);
        config.put("port", 80);
        config.put("targetPort", 8080);
        return config;
    }

    private ObjectNode ingressConfig(String namespace, String name, String host) {
        ObjectNode config = MAPPER.createObjectNode();
        config.put("namespace", namespace);
        config.put("name", name);
        config.put("host", host);
        return config;
    }

    // --- tests ---

    @Test
    void fullLifecycle_declare_compile_provision_readState() {
        // DECLARE
        var decl = new ResourceDeclaration("ns1", "k8s_namespace", null,
                nsConfig("test-ns"), List.of());
        var goals = new InfraGoals("standalone", List.of(decl), List.of());

        // COMPILE
        var graph = compiler.compile(goals, GRAPH_FACTORY);
        assertThat(graph.nodes()).hasSize(1);

        // PROVISION
        var node = graph.nodes().values().iterator().next();
        var ctx = new ProvisionContext("tenant-1", graph);
        var result = nodeProvisioner.provision(node, ctx);
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);

        // READ STATE
        var actualState = stateAdapter.readActual(graph);
        assertThat(actualState.statusOf(NodeId.of("ns1"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void dependencyChain_namespace_deployment_service() {
        // DECLARE: 3 resources with dependency chain ns1 -> d1 -> s1
        var goals = new InfraGoals("standalone", List.of(
                new ResourceDeclaration("ns1", "k8s_namespace", null,
                        nsConfig("production"), List.of()),
                new ResourceDeclaration("d1", "k8s_deployment", null,
                        deploymentConfig("production", "my-deploy", "nginx:1.25"),
                        List.of("ns1")),
                new ResourceDeclaration("s1", "k8s_service", null,
                        serviceConfig("production", "my-svc"),
                        List.of("d1"))),
                List.of());

        // COMPILE
        var graph = compiler.compile(goals, GRAPH_FACTORY);
        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.dependencies()).hasSize(2);

        // PROVISION all 3 nodes in dependency order
        var ctx = new ProvisionContext("tenant-1", graph);
        for (var node : graph.nodes().values()) {
            var result = nodeProvisioner.provision(node, ctx);
            assertThat(result)
                    .as("provisioning %s", node.id())
                    .isInstanceOf(ProvisionResult.Success.class);
        }

        // READ STATE: all 3 should be PRESENT
        var actualState = stateAdapter.readActual(graph);
        assertThat(actualState.statusOf(NodeId.of("ns1"))).contains(NodeStatus.PRESENT);
        assertThat(actualState.statusOf(NodeId.of("d1"))).contains(NodeStatus.PRESENT);
        assertThat(actualState.statusOf(NodeId.of("s1"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void detectDrift_emitsDriftEvent() {
        // Setup: provision a node
        var goals = new InfraGoals("standalone",
                List.of(new ResourceDeclaration("ns1", "k8s_namespace", null,
                        nsConfig("drift-ns"), List.of())),
                List.of());
        var graph = compiler.compile(goals, GRAPH_FACTORY);
        var node = graph.nodes().values().iterator().next();
        nodeProvisioner.provision(node, new ProvisionContext("tenant-1", graph));

        // DRIFT: emit drift event and verify it arrives
        var eventSource = new InfraEventSource();
        var subscriber = eventSource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        eventSource.emitDrift(NodeId.of("ns1"));

        var items = subscriber.getItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).node()).isEqualTo(NodeId.of("ns1"));
        assertThat(items.get(0).newStatus()).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void faultPolicy_provisionFailed_returnsNoMutation() {
        var faultPolicy = new InfraFaultPolicy();
        var faultEvent = new FaultEvent(
                NodeId.of("broken-node"),
                FaultType.PROVISION_FAILED,
                "unsupported resource type");

        var mutations = faultPolicy.onFault(faultEvent, GRAPH_FACTORY.empty());

        assertThat(mutations).isEmpty();
    }

    @Test
    void multipleResourceTypes_allProvisionSuccessfully() {
        // DECLARE: 4 K8s resource types
        var goals = new InfraGoals("standalone", List.of(
                new ResourceDeclaration("ns1", "k8s_namespace", null,
                        nsConfig("multi-ns"), List.of()),
                new ResourceDeclaration("d1", "k8s_deployment", null,
                        deploymentConfig("multi-ns", "app-deploy", "app:latest"),
                        List.of("ns1")),
                new ResourceDeclaration("s1", "k8s_service", null,
                        serviceConfig("multi-ns", "app-svc"),
                        List.of("d1")),
                new ResourceDeclaration("i1", "k8s_ingress", null,
                        ingressConfig("multi-ns", "app-ingress", "app.example.com"),
                        List.of("s1"))),
                List.of());

        // COMPILE
        var graph = compiler.compile(goals, GRAPH_FACTORY);
        assertThat(graph.nodes()).hasSize(4);

        // PROVISION all
        var ctx = new ProvisionContext("tenant-1", graph);
        for (var node : graph.nodes().values()) {
            var result = nodeProvisioner.provision(node, ctx);
            assertThat(result)
                    .as("provisioning %s", node.id())
                    .isInstanceOf(ProvisionResult.Success.class);
        }

        // READ STATE: all 4 should be PRESENT
        var actualState = stateAdapter.readActual(graph);
        assertThat(actualState.statusOf(NodeId.of("ns1"))).contains(NodeStatus.PRESENT);
        assertThat(actualState.statusOf(NodeId.of("d1"))).contains(NodeStatus.PRESENT);
        assertThat(actualState.statusOf(NodeId.of("s1"))).contains(NodeStatus.PRESENT);
        assertThat(actualState.statusOf(NodeId.of("i1"))).contains(NodeStatus.PRESENT);
    }

    @Test
    void readState_unprovisionedNode_returnsUnknown() {
        // DECLARE and COMPILE without provisioning
        var goals = new InfraGoals("standalone",
                List.of(new ResourceDeclaration("ns1", "k8s_namespace", null,
                        nsConfig("ghost-ns"), List.of())),
                List.of());
        var graph = compiler.compile(goals, GRAPH_FACTORY);

        // READ STATE without provisioning -> UNKNOWN (via ResourceStatus.UNKNOWN mapping)
        var actualState = stateAdapter.readActual(graph);
        assertThat(actualState.statusOf(NodeId.of("ns1"))).contains(NodeStatus.UNKNOWN);
    }
}
