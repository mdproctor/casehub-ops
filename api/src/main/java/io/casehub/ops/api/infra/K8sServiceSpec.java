package io.casehub.ops.api.infra;

import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ServiceType;

import java.util.Objects;

public record K8sServiceSpec(
        String namespace,
        String name,
        int port,
        int targetPort,
        ServiceType serviceType,
        Labels labels,
        Labels selector) implements InfraNodeSpec {

    public K8sServiceSpec {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(selector, "selector");
    }

    @Override
    public String resourceType() {
        return "k8s_service";
    }
}
