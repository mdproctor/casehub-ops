package io.casehub.ops.deployment.adaptation;

import io.casehub.ras.api.ActiveSituation;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.deployment.AdaptationActionSpec.ScaleActionSpec;
import io.casehub.ops.api.deployment.AgentNodeSpec;

import java.util.Objects;

/**
 * Scales agent instances based on confidence level.
 * <p>
 * Formula: effective = clamp((confidence - minConfidence) / (1.0 - minConfidence), 0.0, 1.0)
 * Instance count = clamp(min + (int)((max - min) * effective), min, max)
 * <p>
 * Base node is instance 1 (the original). Derived instances use `~` separator: target~2, target~3, etc.
 * Scale-down removes highest-numbered instances first (LIFO).
 */
final class ScaleAction {

    private final ScaleActionSpec spec;

    ScaleAction(ScaleActionSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    DesiredStateGraph apply(DesiredStateGraph graph, ActiveSituation situation, double minConfidence) {
        NodeId baseId = NodeId.of(spec.target());
        DesiredNode baseNode = graph.nodes().get(baseId);
        if (baseNode == null) {
            return graph;
        }
        if (!(baseNode.spec() instanceof AgentNodeSpec agentSpec)) {
            return graph;
        }

        double effective = Math.max(0.0, Math.min(1.0,
            (situation.confidence() - minConfidence) / (1.0 - minConfidence)));
        int instanceCount = Math.max(spec.min(),
            Math.min(spec.max(), spec.min() + (int)((spec.max() - spec.min()) * effective)));

        DesiredStateGraph result = graph;

        // Add needed instances
        for (int i = 2; i <= instanceCount; i++) {
            String derivedId = spec.target() + "~" + i;
            NodeId nid = NodeId.of(derivedId);
            if (!result.nodes().containsKey(nid)) {
                DesiredNode derived = new DesiredNode(
                    nid,
                    baseNode.type(),
                    agentSpec.withAgentId(derivedId),
                    baseNode.humanGating()
                );
                result = result.withNode(derived);
            }
        }

        // Remove excess instances (LIFO)
        for (int i = spec.max(); i > instanceCount; i--) {
            NodeId nid = NodeId.of(spec.target() + "~" + i);
            if (result.nodes().containsKey(nid)) {
                result = result.withoutNode(nid);
            }
        }

        return result;
    }
}
