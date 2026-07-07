package io.casehub.ops.app.k8s;

import java.util.Map;
import java.util.Objects;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sConfigMapSpec;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sConfigMapHandler implements K8sResourceHandler<K8sConfigMapSpec> {

    private static final String FIELD_MANAGER = "casehub-ops";

    @Override
    public Class<K8sConfigMapSpec> specType() {
        return K8sConfigMapSpec.class;
    }

    @Override
    public HasMetadata toResource(K8sConfigMapSpec spec) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(spec.name())
                    .withNamespace(spec.namespace())
                    .withLabels(spec.labels().values())
                .endMetadata()
                .withData(spec.data())
                .build();
    }

    @Override
    public NodeStatus readStatus(KubernetesClient client, K8sConfigMapSpec spec) {
        try {
            ConfigMap actual = client.configMaps()
                    .inNamespace(spec.namespace()).withName(spec.name()).get();
            if (actual == null) return NodeStatus.ABSENT;

            ConfigMap desired = (ConfigMap) toResource(spec);
            return managedFieldsMatch(actual, desired) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        } catch (KubernetesClientException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    @Override
    public void apply(KubernetesClient client, K8sConfigMapSpec spec) {
        ConfigMap resource = (ConfigMap) toResource(spec);
        client.resource(resource).createOr(NonDeletingOperation::update);
    }

    @Override
    public void delete(KubernetesClient client, K8sConfigMapSpec spec) {
        client.configMaps()
                .inNamespace(spec.namespace()).withName(spec.name()).delete();
    }

    private boolean managedFieldsMatch(ConfigMap actual, ConfigMap desired) {
        Map<String, String> actualData = actual.getData();
        Map<String, String> desiredData = desired.getData();
        if (actualData == null) return desiredData == null || desiredData.isEmpty();
        return Objects.equals(actualData, desiredData);
    }
}
