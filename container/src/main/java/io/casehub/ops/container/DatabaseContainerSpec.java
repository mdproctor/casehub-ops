package io.casehub.ops.container;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

import java.util.Objects;

@NodeTypeId("container:database")
public record DatabaseContainerSpec(
    String name,
    String image,
    String databaseName,
    String username,
    String password,
    String dataVolume
) implements ContainerNodeSpec {

    public DatabaseContainerSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(databaseName, "databaseName");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        image = image != null ? image : "postgres:16";
    }

    @Override
    public NodeType nodeType() {
        return ContainerNodeTypes.DATABASE;
    }
}
