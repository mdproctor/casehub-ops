package io.casehub.ops.app.model;

import io.casehub.ops.api.infra.types.HealthCheckSpec;
import io.casehub.ops.api.infra.types.PortMapping;
import io.casehub.ops.api.infra.types.ResourceRequirements;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ServiceDefinition(
        String serviceId,
        String name,
        String image,
        int replicas,
        List<PortMapping> ports,
        Map<String, String> env,
        ResourceRequirements resources,
        List<String> dependsOn,
        Optional<HealthCheckSpec> healthCheck,
        List<String> targetClusters,
        List<ScalingRule> scalingRules) {

    public ServiceDefinition {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(ports, "ports");
        ports = List.copyOf(ports);
        Objects.requireNonNull(env, "env");
        env = Map.copyOf(env);
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(dependsOn, "dependsOn");
        dependsOn = List.copyOf(dependsOn);
        Objects.requireNonNull(healthCheck, "healthCheck");
        Objects.requireNonNull(targetClusters, "targetClusters");
        targetClusters = List.copyOf(targetClusters);
        if (scalingRules == null) scalingRules = List.of();
        scalingRules = List.copyOf(scalingRules);
    }
}
