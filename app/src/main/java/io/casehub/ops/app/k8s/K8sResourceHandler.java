package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;

public interface K8sResourceHandler<S extends InfraNodeSpec> {
    Class<S> specType();

    HasMetadata toResource(S spec);

    NodeStatus readStatus(KubernetesClient client, S spec);

    default java.util.List<io.casehub.ops.app.model.FieldDrift> readDiff(KubernetesClient client, S spec) {
        return java.util.List.of();
    }

    void apply(KubernetesClient client, S spec);

    void delete(KubernetesClient client, S spec);
}
