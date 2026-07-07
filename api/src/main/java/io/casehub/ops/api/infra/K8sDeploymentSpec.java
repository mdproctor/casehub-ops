package io.casehub.ops.api.infra;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.casehub.ops.api.infra.types.HealthCheckSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.PortMapping;
import io.casehub.ops.api.infra.types.ResourceRequirements;

public record K8sDeploymentSpec(
        String namespace,
        String name,
        String image,
        int replicas,
        ResourceRequirements resources,
        Labels labels,
        List<PortMapping> ports,
        Map<String, String> env,
        Optional<HealthCheckSpec> healthCheck) implements InfraNodeSpec {

    public K8sDeploymentSpec {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(ports, "ports");
        ports = List.copyOf(ports);
        Objects.requireNonNull(env, "env");
        env = Map.copyOf(env);
        Objects.requireNonNull(healthCheck, "healthCheck");
    }

    public K8sDeploymentSpec(String namespace, String name, String image, int replicas,
                             ResourceRequirements resources, Labels labels) {
        this(namespace, name, image, replicas, resources, labels,
             List.of(), Map.of(), Optional.empty());
    }

    @Override
    public String resourceType() {
        return "k8s_deployment";
    }
}
