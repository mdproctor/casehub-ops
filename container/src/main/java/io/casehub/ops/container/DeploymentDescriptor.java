package io.casehub.ops.container;

import java.util.Map;
import java.util.Objects;

public record DeploymentDescriptor(
    String appName,
    String image,
    Map<Integer, Integer> ports,
    Map<String, String> env,
    String databaseName
) {

    public DeploymentDescriptor {
        Objects.requireNonNull(appName, "appName");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(databaseName, "databaseName");
        ports = ports != null ? Map.copyOf(ports) : Map.of();
        env = env != null ? Map.copyOf(env) : Map.of();
    }
}
