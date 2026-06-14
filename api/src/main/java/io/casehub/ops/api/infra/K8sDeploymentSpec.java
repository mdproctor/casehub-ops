package io.casehub.ops.api.infra;

import java.util.Objects;

import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ResourceRequirements;

public record K8sDeploymentSpec(
        String namespace,
        String name,
        String image,
        int replicas,
        ResourceRequirements resources,
        Labels labels) implements InfraNodeSpec {

    public K8sDeploymentSpec {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(labels, "labels");
    }

    @Override
    public String resourceType() {
        return "k8s_deployment";
    }
}
