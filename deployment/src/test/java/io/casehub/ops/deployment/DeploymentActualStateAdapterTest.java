package io.casehub.ops.deployment;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DeploymentActualStateAdapterTest {

    private DeploymentActualStateAdapter adapter;
    private SpecHashStore specHashStore;
    private StubDriftChecker agentChecker;
    private StubDriftChecker channelChecker;
    private DefaultDesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        specHashStore = new SpecHashStore();
        agentChecker = new StubDriftChecker("agent");
        channelChecker = new StubDriftChecker("channel");
        graphFactory = new DefaultDesiredStateGraphFactory();
        adapter = new DeploymentActualStateAdapter(
                List.of(agentChecker, channelChecker), specHashStore);
    }

    @Test
    void absentStaysAbsent() {
        agentChecker.nextStatus = NodeStatus.ABSENT;
        var spec = minimalAgent("agent-1");
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("a1"))).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void externalDriftedStaysDrifted() {
        agentChecker.nextStatus = NodeStatus.DRIFTED;
        var spec = minimalAgent("agent-1");
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("a1"))).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void presentWithMatchingHashStaysPresent() {
        agentChecker.nextStatus = NodeStatus.PRESENT;
        var spec = minimalAgent("agent-1");
        specHashStore.record(NodeId.of("a1"), spec);
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("a1"))).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void presentWithDriftedHashBecomesDrifted() {
        agentChecker.nextStatus = NodeStatus.PRESENT;
        var specOld = minimalAgent("agent-1");
        specHashStore.record(NodeId.of("a1"), specOld);
        var specNew = new AgentNodeSpec("agent-1", "Changed", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of());
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), specNew, false);
        var graph = graphFactory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("a1"))).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void unknownStaysUnknown() {
        agentChecker.nextStatus = NodeStatus.UNKNOWN;
        var spec = minimalAgent("agent-1");
        var node = new DesiredNode(NodeId.of("a1"), NodeType.of("agent"), spec, false);
        var graph = graphFactory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("a1"))).isEqualTo(NodeStatus.UNKNOWN);
    }

    @Test
    void unknownNodeTypeReturnsUnknown() {
        var unknownSpec = new NodeSpec() {};
        var node = new DesiredNode(NodeId.of("x1"), NodeType.of("unknown_type"), unknownSpec, false);
        var graph = graphFactory.of(List.of(node), List.of());
        var actual = adapter.readActual(graph);
        assertThat(actual.statuses().get(NodeId.of("x1"))).isEqualTo(NodeStatus.UNKNOWN);
    }

    private AgentNodeSpec minimalAgent(String id) {
        return new AgentNodeSpec(id, "Agent", "worker",
                null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, List.of());
    }

    static class StubDriftChecker implements NodeDriftChecker {
        private final String type;
        NodeStatus nextStatus = NodeStatus.UNKNOWN;
        StubDriftChecker(String type) { this.type = type; }
        @Override public String nodeType() { return type; }
        @Override public NodeStatus check(NodeSpec spec, String tenancyId) { return nextStatus; }
    }
}
