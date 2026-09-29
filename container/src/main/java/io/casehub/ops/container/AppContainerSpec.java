package io.casehub.ops.container;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

import java.util.Map;
import java.util.Objects;

@NodeTypeId("container:app")
public record AppContainerSpec(
    String name,
    String image,
    Map<Integer, Integer> ports,
    Map<String, String> env,
    Map<String, String> volumeMounts
) implements ContainerNodeSpec {

    public AppContainerSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(image, "image");
        ports = ports != null ? Map.copyOf(ports) : Map.of();
        env = env != null ? Map.copyOf(env) : Map.of();
        volumeMounts = volumeMounts != null ? Map.copyOf(volumeMounts) : Map.of();
    }

    @Override
    public NodeType nodeType() {
        return ContainerNodeTypes.APP_CONTAINER;
    }
}
