package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Objects;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sIngressSpec;
import io.casehub.ops.api.infra.types.IngressRule;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValue;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValueBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sIngressHandler implements K8sResourceHandler<K8sIngressSpec> {

    private static final String FIELD_MANAGER = "casehub-ops";

    @Override
    public Class<K8sIngressSpec> specType() {
        return K8sIngressSpec.class;
    }

    @Override
    public HasMetadata toResource(K8sIngressSpec spec) {
        var k8sRules = spec.rules().stream()
                .map(rule -> new HTTPIngressPathBuilder()
                        .withPath(rule.path())
                        .withPathType("Prefix")
                        .withNewBackend()
                            .withNewService()
                                .withName(rule.serviceName())
                                .withNewPort()
                                    .withNumber(rule.servicePort())
                                .endPort()
                            .endService()
                        .endBackend()
                        .build())
                .toList();

        return new IngressBuilder()
                .withNewMetadata()
                    .withName(spec.name())
                    .withNamespace(spec.namespace())
                    .withLabels(spec.labels().values())
                .endMetadata()
                .withNewSpec()
                    .withRules(new IngressRuleBuilder()
                            .withHost(spec.host())
                            .withHttp(new HTTPIngressRuleValueBuilder()
                                    .withPaths(k8sRules)
                                    .build())
                            .build())
                .endSpec()
                .build();
    }

    @Override
    public NodeStatus readStatus(KubernetesClient client, K8sIngressSpec spec) {
        try {
            Ingress actual = client.network().v1().ingresses()
                    .inNamespace(spec.namespace()).withName(spec.name()).get();
            if (actual == null) return NodeStatus.ABSENT;

            Ingress desired = (Ingress) toResource(spec);
            return managedFieldsMatch(actual, desired) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        } catch (KubernetesClientException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    @Override
    public void apply(KubernetesClient client, K8sIngressSpec spec) {
        Ingress resource = (Ingress) toResource(spec);
        client.resource(resource).createOr(NonDeletingOperation::update);
    }

    @Override
    public void delete(KubernetesClient client, K8sIngressSpec spec) {
        client.network().v1().ingresses()
                .inNamespace(spec.namespace()).withName(spec.name()).delete();
    }

    private boolean managedFieldsMatch(Ingress actual, Ingress desired) {
        var actualRules = actual.getSpec().getRules();
        var desiredRules = desired.getSpec().getRules();

        if (actualRules == null || desiredRules == null) return actualRules == desiredRules;
        if (actualRules.size() != desiredRules.size()) return false;

        for (int i = 0; i < actualRules.size(); i++) {
            var ar = actualRules.get(i);
            var dr = desiredRules.get(i);
            if (!Objects.equals(ar.getHost(), dr.getHost())) return false;

            var actualPaths = ar.getHttp().getPaths();
            var desiredPaths = dr.getHttp().getPaths();
            if (actualPaths == null || desiredPaths == null) return actualPaths == desiredPaths;
            if (actualPaths.size() != desiredPaths.size()) return false;

            for (int j = 0; j < actualPaths.size(); j++) {
                if (!Objects.equals(actualPaths.get(j).getPath(), desiredPaths.get(j).getPath())) return false;
                if (!Objects.equals(
                        actualPaths.get(j).getBackend().getService().getName(),
                        desiredPaths.get(j).getBackend().getService().getName())) return false;
                if (!Objects.equals(
                        actualPaths.get(j).getBackend().getService().getPort().getNumber(),
                        desiredPaths.get(j).getBackend().getService().getPort().getNumber())) return false;
            }
        }

        return true;
    }
}
