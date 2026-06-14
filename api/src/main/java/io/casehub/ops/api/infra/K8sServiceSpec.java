package io.casehub.ops.api.infra;

import java.util.Objects;

import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ServiceType;

public record K8sServiceSpec(
        String namespace,
        String name,
        int port,
        int targetPort,
        ServiceType serviceType,
        Labels labels) implements InfraNodeSpec {

    public K8sServiceSpec {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(labels, "labels");
    }

    @Override
    public String resourceType() {
        return "k8s_service";
    }
}
