package io.casehub.ops.api.infra;

import java.util.Map;
import java.util.Objects;

import io.casehub.ops.api.infra.types.Labels;

public record K8sConfigMapSpec(
        String namespace,
        String name,
        Map<String, String> data,
        Labels labels) implements InfraNodeSpec {

    public K8sConfigMapSpec {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(data, "data");
        data = Map.copyOf(data);
        Objects.requireNonNull(labels, "labels");
    }

    @Override
    public String resourceType() {
        return "k8s_configmap";
    }
}
