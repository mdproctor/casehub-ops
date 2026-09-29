package io.casehub.ops.container;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

import java.util.Objects;

@NodeTypeId("container:volume")
public record VolumeSpec(String name, String driver) implements ContainerNodeSpec {

    public VolumeSpec {
        Objects.requireNonNull(name, "name");
        driver = driver != null ? driver : "local";
    }

    @Override
    public NodeType nodeType() {
        return ContainerNodeTypes.VOLUME;
    }
}
