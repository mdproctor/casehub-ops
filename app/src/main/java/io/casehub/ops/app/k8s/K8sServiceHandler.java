package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sServiceSpec;
import io.casehub.ops.api.infra.types.ServiceType;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;

@ApplicationScoped
public class K8sServiceHandler implements K8sResourceHandler<K8sServiceSpec> {

    private static final String FIELD_MANAGER = "casehub-ops";

    @Override
    public Class<K8sServiceSpec> specType() {
        return K8sServiceSpec.class;
    }

    @Override
    public HasMetadata toResource(K8sServiceSpec spec) {
        return new ServiceBuilder()
                       .withNewMetadata()
                       .withName(spec.name())
                       .withNamespace(spec.namespace())
                       .withLabels(spec.labels().values())
                       .endMetadata()
                       .withNewSpec()
                       .withType(toK8sServiceType(spec.serviceType()))
                       .withSelector(spec.selector().values())
                       .withPorts(new ServicePortBuilder()
                                          .withPort(spec.port())
                                          .withTargetPort(new IntOrString(spec.targetPort()))
                                          .withProtocol("TCP")
                                          .build())
                       .endSpec()
                       .build();}

    @Override
    public NodeStatus readStatus(KubernetesClient client, K8sServiceSpec spec) {
        try {
            Service actual = client.services()
                    .inNamespace(spec.namespace()).withName(spec.name()).get();
            if (actual == null) return NodeStatus.ABSENT;

            Service desired = (Service) toResource(spec);
            return managedFieldsMatch(actual, desired) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        } catch (KubernetesClientException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    @Override
    public void apply(KubernetesClient client, K8sServiceSpec spec) {
        Service resource = (Service) toResource(spec);
        client.resource(resource).createOr(NonDeletingOperation::update);
    }

    @Override
    public void delete(KubernetesClient client, K8sServiceSpec spec) {
        client.services()
                .inNamespace(spec.namespace()).withName(spec.name()).delete();
    }

    private boolean managedFieldsMatch(Service actual, Service desired) {
        if (!Objects.equals(actual.getSpec().getType(), desired.getSpec().getType())) return false;

        var actualPorts = actual.getSpec().getPorts();
        var desiredPorts = desired.getSpec().getPorts();
        if (actualPorts == null || desiredPorts == null) return actualPorts == desiredPorts;
        if (actualPorts.size() != desiredPorts.size()) return false;

        for (int i = 0; i < actualPorts.size(); i++) {
            if (!Objects.equals(actualPorts.get(i).getPort(), desiredPorts.get(i).getPort())) return false;
            if (!Objects.equals(actualPorts.get(i).getTargetPort(), desiredPorts.get(i).getTargetPort())) return false;
        }

        return true;
    }

    private String toK8sServiceType(ServiceType serviceType) {
        return switch (serviceType) {
            case CLUSTER_IP -> "ClusterIP";
            case NODE_PORT -> "NodePort";
            case LOAD_BALANCER -> "LoadBalancer";
        };
    }
}
