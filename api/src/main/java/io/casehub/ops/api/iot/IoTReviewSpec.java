package io.casehub.ops.api.iot;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;

import java.util.Objects;

public record IoTReviewSpec(NodeId faultedNode, String reason) implements NodeSpec {
    public IoTReviewSpec {
        Objects.requireNonNull(faultedNode, "faultedNode");
        Objects.requireNonNull(reason, "reason");
    }
}
