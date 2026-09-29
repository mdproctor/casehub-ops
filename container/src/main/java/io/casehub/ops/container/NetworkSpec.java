package io.casehub.ops.container;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

import java.util.Objects;

@NodeTypeId("container:network")
public record NetworkSpec(String name) implements ContainerNodeSpec {

    public NetworkSpec {
        Objects.requireNonNull(name, "name");
    }

    @Override
    public NodeType nodeType() {
        return ContainerNodeTypes.NETWORK;
    }
}
