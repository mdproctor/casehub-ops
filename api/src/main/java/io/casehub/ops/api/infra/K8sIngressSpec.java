package io.casehub.ops.api.infra;

import java.util.List;
import java.util.Objects;

import io.casehub.ops.api.infra.types.IngressRule;
import io.casehub.ops.api.infra.types.Labels;

public record K8sIngressSpec(
        String namespace,
        String name,
        String host,
        List<IngressRule> rules,
        Labels labels) implements InfraNodeSpec {

    public K8sIngressSpec {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(rules, "rules");
        rules = List.copyOf(rules);
        Objects.requireNonNull(labels, "labels");
    }

    @Override
    public String resourceType() {
        return "k8s_ingress";
    }
}
