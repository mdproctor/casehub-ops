package io.casehub.ops.api.infra;

import java.util.Objects;

import io.casehub.ops.api.infra.types.Labels;

public record K8sNamespaceSpec(String name, Labels labels) implements InfraNodeSpec {

    public K8sNamespaceSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(labels, "labels");
    }

    @Override
    public String resourceType() {
        return "k8s_namespace";
    }
}
