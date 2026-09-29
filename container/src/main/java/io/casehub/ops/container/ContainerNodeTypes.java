package io.casehub.ops.container;

import io.casehub.desiredstate.api.NodeType;

public final class ContainerNodeTypes {

    public static final NodeType APP_CONTAINER = NodeType.of("container:app");
    public static final NodeType DATABASE = NodeType.of("container:database");
    public static final NodeType NETWORK = NodeType.of("container:network");
    public static final NodeType VOLUME = NodeType.of("container:volume");

    private ContainerNodeTypes() {}
}
