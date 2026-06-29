package io.casehub.ops.deployment.adaptation;

import io.casehub.desiredstate.api.ActiveSituation;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.AgentNodeSpec;
import io.casehub.ops.api.deployment.AdaptationActionSpec.ScaleActionSpec;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScaleActionTest {

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void confidenceAtMinThresholdProducesMinInstances() {
        var spec = new ScaleActionSpec("risk-agent", 1, 5);
        var action = new ScaleAction(spec);

        var baseNode = createAgentNode("risk-agent");
        var graph = factory.of(List.of(baseNode), List.of());
        var situation = new ActiveSituation("high-load", 0.7, Map.of(), Instant.now());

        var result = action.apply(graph, situation, 0.7);

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes()).containsKey(NodeId.of("risk-agent"));
    }

    @Test
    void confidenceAtMaxProducesMaxInstances() {
        var spec = new ScaleActionSpec("risk-agent", 1, 5);
        var action = new ScaleAction(spec);

        var baseNode = createAgentNode("risk-agent");
        var graph = factory.of(List.of(baseNode), List.of());
        var situation = new ActiveSituation("high-load", 1.0, Map.of(), Instant.now());

        var result = action.apply(graph, situation, 0.7);

        assertThat(result.nodes()).hasSize(5);
        assertThat(result.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2"),
            NodeId.of("risk-agent~3"),
            NodeId.of("risk-agent~4"),
            NodeId.of("risk-agent~5")
        );
    }

    @Test
    void midRangeConfidenceProducesMidRangeInstances() {
        var spec = new ScaleActionSpec("risk-agent", 1, 5);
        var action = new ScaleAction(spec);

        var baseNode = createAgentNode("risk-agent");
        var graph = factory.of(List.of(baseNode), List.of());
        // effective = (0.85 - 0.7) / (1.0 - 0.7) = 0.15 / 0.3 = 0.5
        // instances = 1 + (int)((5 - 1) * 0.5) = 1 + 2 = 3
        var situation = new ActiveSituation("high-load", 0.85, Map.of(), Instant.now());

        var result = action.apply(graph, situation, 0.7);

        assertThat(result.nodes()).hasSize(3);
        assertThat(result.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2"),
            NodeId.of("risk-agent~3")
        );
    }

    @Test
    void derivedInstancesHaveCorrectAgentId() {
        var spec = new ScaleActionSpec("risk-agent", 1, 3);
        var action = new ScaleAction(spec);

        var baseNode = createAgentNode("risk-agent");
        var graph = factory.of(List.of(baseNode), List.of());
        var situation = new ActiveSituation("high-load", 1.0, Map.of(), Instant.now());

        var result = action.apply(graph, situation, 0.7);

        var derived2 = result.nodes().get(NodeId.of("risk-agent~2"));
        assertThat(derived2).isNotNull();
        assertThat(derived2.spec()).isInstanceOf(AgentNodeSpec.class);
        var agentSpec2 = (AgentNodeSpec) derived2.spec();
        assertThat(agentSpec2.agentId()).isEqualTo("risk-agent~2");

        var derived3 = result.nodes().get(NodeId.of("risk-agent~3"));
        assertThat(derived3).isNotNull();
        var agentSpec3 = (AgentNodeSpec) derived3.spec();
        assertThat(agentSpec3.agentId()).isEqualTo("risk-agent~3");
    }

    @Test
    void scaleDownRemovesHighestNumberedFirst() {
        var spec = new ScaleActionSpec("risk-agent", 1, 5);
        var action = new ScaleAction(spec);

        var baseNode = createAgentNode("risk-agent");
        var graph = factory.of(List.of(baseNode), List.of());

        // Scale up to 5
        var situationMax = new ActiveSituation("high-load", 1.0, Map.of(), Instant.now());
        var graphScaledUp = action.apply(graph, situationMax, 0.7);
        assertThat(graphScaledUp.nodes()).hasSize(5);

        // Scale down to 2
        // effective = (0.73 - 0.7) / (1.0 - 0.7) = 0.1
        // instances = 1 + (int)((5 - 1) * 0.1) = 1 + 0 = 1
        // Actually let's use 0.775 to get 2 instances
        // effective = (0.775 - 0.7) / 0.3 = 0.25
        // instances = 1 + (int)(4 * 0.25) = 1 + 1 = 2
        var situationDown = new ActiveSituation("high-load", 0.775, Map.of(), Instant.now());
        var graphScaledDown = action.apply(graphScaledUp, situationDown, 0.7);

        assertThat(graphScaledDown.nodes()).hasSize(2);
        assertThat(graphScaledDown.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2")
        );
        assertThat(graphScaledDown.nodes()).doesNotContainKeys(
            NodeId.of("risk-agent~3"),
            NodeId.of("risk-agent~4"),
            NodeId.of("risk-agent~5")
        );
    }

    @Test
    void baseNodeAlwaysPresent() {
        var spec = new ScaleActionSpec("risk-agent", 2, 5);
        var action = new ScaleAction(spec);

        var baseNode = createAgentNode("risk-agent");
        var graph = factory.of(List.of(baseNode), List.of());
        var situation = new ActiveSituation("high-load", 1.0, Map.of(), Instant.now());

        var result = action.apply(graph, situation, 0.7);

        // min=2, max=5, confidence=1.0 → 5 instances
        assertThat(result.nodes()).hasSize(5);
        assertThat(result.nodes()).containsKey(NodeId.of("risk-agent"));
        assertThat(result.nodes()).containsKeys(
            NodeId.of("risk-agent"),
            NodeId.of("risk-agent~2"),
            NodeId.of("risk-agent~3"),
            NodeId.of("risk-agent~4"),
            NodeId.of("risk-agent~5")
        );
    }

    @Test
    void nonAgentNodeSpecReturnsUnchangedGraph() {
        var spec = new ScaleActionSpec("trust-policy", 1, 5);
        var action = new ScaleAction(spec);

        var trustNode = new DesiredNode(
            NodeId.of("trust-policy"),
            NodeType.of("trust_policy"),
            new TrustPolicyNodeSpec("risk-assessment", 0.8, 10, 0.05, 0.7, Map.of(), false),
            false
        );
        var graph = factory.of(List.of(trustNode), List.of());
        var situation = new ActiveSituation("high-load", 1.0, Map.of(), Instant.now());

        var result = action.apply(graph, situation, 0.7);

        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes()).containsOnlyKeys(NodeId.of("trust-policy"));
    }

    @Test
    void missingTargetReturnsUnchangedGraph() {
        var spec = new ScaleActionSpec("missing-agent", 1, 5);
        var action = new ScaleAction(spec);

        var baseNode = createAgentNode("risk-agent");
        var graph = factory.of(List.of(baseNode), List.of());
        var situation = new ActiveSituation("high-load", 1.0, Map.of(), Instant.now());

        var result = action.apply(graph, situation, 0.7);

        assertThat(result).isSameAs(graph);
    }

    private DesiredNode createAgentNode(String agentId) {
        var spec = new AgentNodeSpec(
            agentId, "Risk Agent", "risk-assessment",
            "anthropic", "claude", "opus-4.6-2026",
            "1.0.0", "fp-abc123",
            null, null, null, null,
            List.of(), null, null, null, null, List.of()
        );
        return new DesiredNode(NodeId.of(agentId), NodeType.of("agent"), spec, false);
    }
}
