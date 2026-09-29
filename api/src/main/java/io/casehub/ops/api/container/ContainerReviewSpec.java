package io.casehub.ops.api.container;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

import java.util.Objects;

public record ContainerReviewSpec(NodeId faultedNode, String reason) implements NodeSpec {
    public ContainerReviewSpec {
        Objects.requireNonNull(faultedNode, "faultedNode");
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    public NodeType nodeType() {
        return NodeType.of("container-review");
    }
}
