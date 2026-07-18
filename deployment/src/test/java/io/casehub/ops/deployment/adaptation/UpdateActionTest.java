package io.casehub.ops.deployment.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.api.deployment.AdaptationActionSpec.UpdateActionSpec;
import io.casehub.ops.api.deployment.TrustPolicyNodeSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateActionTest {

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jacksonTreeMergeUpdatesSpecifiedFields() {
        var spec = new UpdateActionSpec(
            "risk-policy",
            "trust_policy",
            Map.of("threshold", 0.9, "borderlineMargin", 0.1)
        );
        var action = new UpdateAction(spec);

        var originalSpec = new TrustPolicyNodeSpec(
            "risk-assessment", 0.7, 10, 0.05, 0.6, Map.of("precision", 0.8), true
        );
        var node = new DesiredNode(
            NodeId.of("risk-policy"),
            NodeType.of("trust_policy"),
            originalSpec,
            io.casehub.desiredstate.api.HumanGating.NONE
        );
        var graph = factory.of(List.of(node), List.of());

        var result = action.apply(graph, mapper);

        var updatedNode = result.nodes().get(NodeId.of("risk-policy"));
        assertThat(updatedNode).isNotNull();
        assertThat(updatedNode.spec()).isInstanceOf(TrustPolicyNodeSpec.class);

        var updatedSpec = (TrustPolicyNodeSpec) updatedNode.spec();
        assertThat(updatedSpec.threshold()).isEqualTo(0.9);
        assertThat(updatedSpec.borderlineMargin()).isEqualTo(0.1);
        // Other fields preserved
        assertThat(updatedSpec.capability()).isEqualTo("risk-assessment");
        assertThat(updatedSpec.minimumObservations()).isEqualTo(10);
        assertThat(updatedSpec.blendFactor()).isEqualTo(0.6);
        assertThat(updatedSpec.qualityFloors()).containsEntry("precision", 0.8);
        assertThat(updatedSpec.bootstrapEscalationRequired()).isTrue();
    }

    @Test
    void targetResolutionByNodeId() {
        var spec = new UpdateActionSpec(
            "risk-policy",
            null,
            Map.of("threshold", 0.95)
        );
        var action = new UpdateAction(spec);

        var originalSpec = new TrustPolicyNodeSpec(
            "risk-assessment", 0.7, 10, 0.05, 0.6, Map.of(), false
        );
        var node = new DesiredNode(
            NodeId.of("risk-policy"),
            NodeType.of("trust_policy"),
            originalSpec,
            io.casehub.desiredstate.api.HumanGating.NONE
        );
        var graph = factory.of(List.of(node), List.of());

        var result = action.apply(graph, mapper);

        var updatedNode = result.nodes().get(NodeId.of("risk-policy"));
        assertThat(updatedNode).isNotNull();
        var updatedSpec = (TrustPolicyNodeSpec) updatedNode.spec();
        assertThat(updatedSpec.threshold()).isEqualTo(0.95);
    }

    @Test
    void targetResolutionWithNodeTypeFilter() {
        var spec = new UpdateActionSpec(
            "risk-policy",
            "trust_policy",
            Map.of("threshold", 0.85)
        );
        var action = new UpdateAction(spec);

        var originalSpec = new TrustPolicyNodeSpec(
            "risk-assessment", 0.7, 10, 0.05, 0.6, Map.of(), false
        );
        var node = new DesiredNode(
            NodeId.of("risk-policy"),
            NodeType.of("trust_policy"),
            originalSpec,
            io.casehub.desiredstate.api.HumanGating.NONE
        );
        var graph = factory.of(List.of(node), List.of());

        var result = action.apply(graph, mapper);

        var updatedNode = result.nodes().get(NodeId.of("risk-policy"));
        assertThat(updatedNode).isNotNull();
        var updatedSpec = (TrustPolicyNodeSpec) updatedNode.spec();
        assertThat(updatedSpec.threshold()).isEqualTo(0.85);
    }

    @Test
    void missingTargetReturnsUnchangedGraph() {
        var spec = new UpdateActionSpec(
            "missing-node",
            null,
            Map.of("threshold", 0.9)
        );
        var action = new UpdateAction(spec);

        var originalSpec = new TrustPolicyNodeSpec(
            "risk-assessment", 0.7, 10, 0.05, 0.6, Map.of(), false
        );
        var node = new DesiredNode(
            NodeId.of("risk-policy"),
            NodeType.of("trust_policy"),
            originalSpec,
            io.casehub.desiredstate.api.HumanGating.NONE
        );
        var graph = factory.of(List.of(node), List.of());

        var result = action.apply(graph, mapper);

        assertThat(result).isSameAs(graph);
    }

    @Test
    void nodeTypeMismatchReturnsUnchangedGraph() {
        var spec = new UpdateActionSpec(
            "risk-policy",
            "agent",  // wrong type
            Map.of("threshold", 0.9)
        );
        var action = new UpdateAction(spec);

        var originalSpec = new TrustPolicyNodeSpec(
            "risk-assessment", 0.7, 10, 0.05, 0.6, Map.of(), false
        );
        var node = new DesiredNode(
            NodeId.of("risk-policy"),
            NodeType.of("trust_policy"),
            originalSpec,
            io.casehub.desiredstate.api.HumanGating.NONE
        );
        var graph = factory.of(List.of(node), List.of());

        var result = action.apply(graph, mapper);

        assertThat(result).isSameAs(graph);
    }
}
