package io.casehub.ops.app.k8s;

import java.util.Map;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sNamespaceHandler implements K8sResourceHandler<K8sNamespaceSpec> {

    private static final String FIELD_MANAGER = "casehub-ops";

    @Override
    public Class<K8sNamespaceSpec> specType() {
        return K8sNamespaceSpec.class;
    }

    @Override
    public HasMetadata toResource(K8sNamespaceSpec spec) {
        return new NamespaceBuilder()
                .withNewMetadata()
                    .withName(spec.name())
                    .withLabels(spec.labels().values())
                .endMetadata()
                .build();
    }

    @Override
    public NodeStatus readStatus(KubernetesClient client, K8sNamespaceSpec spec) {
        try {
            Namespace actual = client.namespaces().withName(spec.name()).get();
            if (actual == null) {
                return NodeStatus.ABSENT;
            }
            Namespace desired = (Namespace) toResource(spec);
            return labelsMatch(actual, desired) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        } catch (KubernetesClientException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    @Override
    public void apply(KubernetesClient client, K8sNamespaceSpec spec) {
        Namespace resource = (Namespace) toResource(spec);
        client.resource(resource).createOr(NonDeletingOperation::update);
    }

    @Override
    public void delete(KubernetesClient client, K8sNamespaceSpec spec) {
        client.namespaces().withName(spec.name()).delete();
    }

    private boolean labelsMatch(Namespace actual, Namespace desired) {
        Map<String, String> actualLabels = actual.getMetadata().getLabels();
        Map<String, String> desiredLabels = desired.getMetadata().getLabels();
        if (actualLabels == null) return desiredLabels == null || desiredLabels.isEmpty();
        return desiredLabels != null && desiredLabels.entrySet().stream()
                .allMatch(e -> e.getValue().equals(actualLabels.get(e.getKey())));
    }
}
