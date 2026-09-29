package io.casehub.ops.container;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerGoalCompilerTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void compilesDeploymentWithFourNodes() {
        var descriptor = new DeploymentDescriptor(
            "fsitrading", "casehub/fsitrading:latest",
            Map.of(8080, 18080), Map.of("QUARKUS_PROFILE", "prod"),
            "fsitrading_db");

        var compiler = new ContainerGoalCompiler();
        var result = compiler.compile(descriptor, factory);

        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.nodes()).hasSize(4);
    }

    @Test
    void graphContainsAllNodeTypes() {
        var descriptor = new DeploymentDescriptor(
            "myapp", "img:1", Map.of(8080, 8080), Map.of(), "mydb");

        var graph = compileSingleGraph(descriptor);

        var types = graph.nodes().values().stream()
            .map(DesiredNode::type)
            .toList();

        assertThat(types).containsExactlyInAnyOrder(
            ContainerNodeTypes.NETWORK,
            ContainerNodeTypes.VOLUME,
            ContainerNodeTypes.DATABASE,
            ContainerNodeTypes.APP_CONTAINER);
    }

    @Test
    void appContainerDependsOnDatabaseAndNetwork() {
        var descriptor = new DeploymentDescriptor(
            "myapp", "img:1", Map.of(8080, 8080), Map.of(), "mydb");

        var graph = compileSingleGraph(descriptor);

        var appNodeId = nodeIdByType(graph, ContainerNodeTypes.APP_CONTAINER);
        var dbNodeId = nodeIdByType(graph, ContainerNodeTypes.DATABASE);
        var netNodeId = nodeIdByType(graph, ContainerNodeTypes.NETWORK);

        assertThat(graph.dependenciesOf(appNodeId))
            .containsExactlyInAnyOrder(dbNodeId, netNodeId);
    }

    @Test
    void databaseDependsOnNetworkAndVolume() {
        var descriptor = new DeploymentDescriptor(
            "myapp", "img:1", Map.of(8080, 8080), Map.of(), "mydb");

        var graph = compileSingleGraph(descriptor);

        var dbNodeId = nodeIdByType(graph, ContainerNodeTypes.DATABASE);
        var netNodeId = nodeIdByType(graph, ContainerNodeTypes.NETWORK);
        var volNodeId = nodeIdByType(graph, ContainerNodeTypes.VOLUME);

        assertThat(graph.dependenciesOf(dbNodeId))
            .containsExactlyInAnyOrder(netNodeId, volNodeId);
    }

    @Test
    void networkAndVolumeAreRoots() {
        var descriptor = new DeploymentDescriptor(
            "myapp", "img:1", Map.of(8080, 8080), Map.of(), "mydb");

        var graph = compileSingleGraph(descriptor);

        var netNodeId = nodeIdByType(graph, ContainerNodeTypes.NETWORK);
        var volNodeId = nodeIdByType(graph, ContainerNodeTypes.VOLUME);

        assertThat(graph.roots()).containsExactlyInAnyOrder(netNodeId, volNodeId);
    }

    @Test
    void appContainerIncludesJdbcUrlInEnv() {
        var descriptor = new DeploymentDescriptor(
            "myapp", "img:1", Map.of(8080, 8080), Map.of("MY_VAR", "val"), "mydb");

        var graph = compileSingleGraph(descriptor);

        var appNode = graph.nodes().values().stream()
            .filter(n -> n.type().equals(ContainerNodeTypes.APP_CONTAINER))
            .findFirst().orElseThrow();

        var appSpec = (AppContainerSpec) appNode.spec();
        assertThat(appSpec.env()).containsKey("QUARKUS_DATASOURCE_JDBC_URL");
        assertThat(appSpec.env().get("QUARKUS_DATASOURCE_JDBC_URL"))
            .contains("mydb");
        assertThat(appSpec.env()).containsEntry("MY_VAR", "val");
    }

    private io.casehub.desiredstate.api.DesiredStateGraph compileSingleGraph(DeploymentDescriptor descriptor) {
        var result = new ContainerGoalCompiler().compile(descriptor, factory);
        return ((CompilationResult.SingleGraph) result).graph();
    }

    private NodeId nodeIdByType(io.casehub.desiredstate.api.DesiredStateGraph graph,
                                io.casehub.desiredstate.api.NodeType type) {
        return graph.nodes().entrySet().stream()
            .filter(e -> e.getValue().type().equals(type))
            .map(Map.Entry::getKey)
            .findFirst().orElseThrow();
    }
}
