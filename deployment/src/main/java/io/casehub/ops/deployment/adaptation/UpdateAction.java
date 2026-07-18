package io.casehub.ops.deployment.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.ops.api.deployment.AdaptationActionSpec.UpdateActionSpec;

import java.util.Objects;

/**
 * Updates fields on an existing node using Jackson tree-merge.
 * <p>
 * Target resolution: finds node by nodeId. If nodeType is specified, filters by type.
 * Jackson tree-merge: base spec fields + override fields → merged spec.
 */
final class UpdateAction {

    private final UpdateActionSpec spec;

    UpdateAction(UpdateActionSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    DesiredStateGraph apply(DesiredStateGraph graph, ObjectMapper mapper) {
        NodeId targetId = NodeId.of(spec.target());
        DesiredNode node = graph.nodes().get(targetId);
        if (node == null) {
            return graph;
        }

        // Filter by nodeType if specified
        if (spec.nodeType() != null && !node.type().value().equals(spec.nodeType())) {
            return graph;
        }

        try {
            ObjectNode base = mapper.valueToTree(node.spec());
            ObjectNode overrides = mapper.valueToTree(spec.fields());
            base.setAll(overrides);
            NodeSpec merged = mapper.treeToValue(base, node.spec().getClass());

            DesiredNode adaptedNode = new DesiredNode(targetId, node.type(), merged, node.humanGating());
            return graph.withMutation(new GraphMutation.UpdateNode(targetId, adaptedNode));
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to merge fields for node " + targetId + ": " + e.getMessage(), e);
        }
    }
}
