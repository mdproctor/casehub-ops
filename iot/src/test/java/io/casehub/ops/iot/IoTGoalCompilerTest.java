package io.casehub.ops.iot;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.iot.api.DeviceClass;
import io.casehub.ops.api.iot.DeviceConfigSpec;
import io.casehub.ops.api.iot.PhysicalDeviceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTGoalCompilerTest {

    private IoTGoalCompiler compiler;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        compiler = new IoTGoalCompiler();
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void physicalTrue_createsTwoNodesAndDependency() {
        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("thermo-1", DeviceClass.THERMOSTAT, "Living Room", true,
                Map.of("targetTemperature", Map.of("value", 22, "unit", "CELSIUS")),
                List.of())));

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.nodes().get(NodeId.of("thermo-1")).requiresHuman()).isTrue();
        assertThat(graph.nodes().get(NodeId.of("thermo-1")).spec()).isInstanceOf(PhysicalDeviceSpec.class);
        assertThat(graph.nodes().get(NodeId.of("thermo-1-config")).requiresHuman()).isFalse();
        assertThat(graph.nodes().get(NodeId.of("thermo-1-config")).spec()).isInstanceOf(DeviceConfigSpec.class);
        assertThat(graph.dependencies()).contains(new Dependency(NodeId.of("thermo-1-config"), NodeId.of("thermo-1")));
    }

    @Test
    void physicalFalse_createsSingleConfigNode() {
        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("light-1", DeviceClass.LIGHT, "Porch", false,
                Map.of("isOn", true), List.of())));

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        assertThat(graph.nodes()).hasSize(1);
        DesiredNode node = graph.nodes().get(NodeId.of("light-1"));
        assertThat(node).isNotNull();
        assertThat(node.requiresHuman()).isFalse();
        assertThat(node.type().value()).isEqualTo("device-config");
    }

    @Test
    void dependsOn_createsDependencyEdge() {
        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("hub", DeviceClass.SWITCH, "Hub", true,
                Map.of("isOn", true), List.of()),
            new IoTDeviceGoal("sensor", DeviceClass.SENSOR, "Hallway", true,
                Map.of(), List.of("hub"))));

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();

        assertThat(graph.dependencies()).contains(
            new Dependency(NodeId.of("sensor"), NodeId.of("hub")));
    }

    @Test
    void emptyDeviceList_emptyGraph() {
        var goals = new IoTGoals("tenant-1", List.of());
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(goals, factory)).graph();
        assertThat(graph.isEmpty()).isTrue();
    }

    @Test
    void duplicateDeviceId_throws() {
        var goals = new IoTGoals("tenant-1", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.SWITCH, "A", true, Map.of(), List.of()),
            new IoTDeviceGoal("dev-1", DeviceClass.LIGHT, "B", true, Map.of(), List.of())));

        assertThatThrownBy(() -> compiler.compile(goals, factory))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dev-1");
    }
}
