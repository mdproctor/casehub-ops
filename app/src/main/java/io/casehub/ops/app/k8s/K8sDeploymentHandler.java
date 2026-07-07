package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K8sDeploymentHandler implements K8sResourceHandler<K8sDeploymentSpec> {

    private static final String FIELD_MANAGER = "casehub-ops";

    @Override
    public Class<K8sDeploymentSpec> specType() {
        return K8sDeploymentSpec.class;
    }

    @Override
    public HasMetadata toResource(K8sDeploymentSpec spec) {
        var containerBuilder = new ContainerBuilder()
                .withName(spec.name())
                .withImage(spec.image())
                .withResources(new ResourceRequirementsBuilder()
                        .addToRequests("cpu", new Quantity(spec.resources().cpuRequest()))
                        .addToRequests("memory", new Quantity(spec.resources().memoryRequest()))
                        .addToLimits("cpu", new Quantity(spec.resources().cpuLimit()))
                        .addToLimits("memory", new Quantity(spec.resources().memoryLimit()))
                        .build())
                .withPorts(spec.ports().stream()
                        .map(p -> new ContainerPortBuilder()
                                .withContainerPort(p.containerPort())
                                .withProtocol(p.protocol())
                                .build())
                        .toList())
                .withEnv(spec.env().entrySet().stream()
                        .map(e -> new EnvVarBuilder()
                                .withName(e.getKey())
                                .withValue(e.getValue())
                                .build())
                        .toList());

        spec.healthCheck().ifPresent(hc -> {
            Probe probe = new ProbeBuilder()
                    .withNewHttpGet()
                        .withPath(hc.path())
                        .withPort(new IntOrString(hc.port()))
                    .endHttpGet()
                    .withInitialDelaySeconds(hc.initialDelaySeconds())
                    .withPeriodSeconds(hc.periodSeconds())
                    .build();
            containerBuilder.withLivenessProbe(probe);
            containerBuilder.withReadinessProbe(probe);
        });

        return new DeploymentBuilder()
                .withNewMetadata()
                    .withName(spec.name())
                    .withNamespace(spec.namespace())
                    .withLabels(spec.labels().values())
                .endMetadata()
                .withNewSpec()
                    .withReplicas(spec.replicas())
                    .withNewSelector()
                        .withMatchLabels(Map.of("app", spec.name()))
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(Map.of("app", spec.name()))
                        .endMetadata()
                        .withNewSpec()
                            .withContainers(containerBuilder.build())
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    @Override
    public NodeStatus readStatus(KubernetesClient client, K8sDeploymentSpec spec) {
        try {
            Deployment actual = client.apps().deployments()
                    .inNamespace(spec.namespace()).withName(spec.name()).get();
            if (actual == null) return NodeStatus.ABSENT;

            Deployment desired = (Deployment) toResource(spec);
            return managedFieldsMatch(actual, desired) ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        } catch (KubernetesClientException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    @Override
    public void apply(KubernetesClient client, K8sDeploymentSpec spec) {
        Deployment resource = (Deployment) toResource(spec);
        client.resource(resource).createOr(NonDeletingOperation::update);
    }

    @Override
    public void delete(KubernetesClient client, K8sDeploymentSpec spec) {
        client.apps().deployments()
                .inNamespace(spec.namespace()).withName(spec.name()).delete();
    }

    private boolean managedFieldsMatch(Deployment actual, Deployment desired) {
        var actualContainer = actual.getSpec().getTemplate().getSpec().getContainers().get(0);
        var desiredContainer = desired.getSpec().getTemplate().getSpec().getContainers().get(0);

        if (!Objects.equals(actual.getSpec().getReplicas(), desired.getSpec().getReplicas())) return false;
        if (!Objects.equals(actualContainer.getImage(), desiredContainer.getImage())) return false;

        Map<String, String> actualEnv = actualContainer.getEnv() == null
                ? Map.of()
                : actualContainer.getEnv().stream()
                    .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
        Map<String, String> desiredEnv = desiredContainer.getEnv() == null
                ? Map.of()
                : desiredContainer.getEnv().stream()
                    .collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
        if (!actualEnv.equals(desiredEnv)) return false;

        // Resource limits and requests
        if (!Objects.equals(
                actualContainer.getResources().getRequests(),
                desiredContainer.getResources().getRequests())) return false;
        if (!Objects.equals(
                actualContainer.getResources().getLimits(),
                desiredContainer.getResources().getLimits())) return false;

        // Container ports
        var actualPorts = actualContainer.getPorts();
        var desiredPorts = desiredContainer.getPorts();
        if (actualPorts == null || desiredPorts == null) {
            if (actualPorts != desiredPorts) return false;
        } else {
            if (actualPorts.size() != desiredPorts.size()) return false;
            for (int i = 0; i < actualPorts.size(); i++) {
                if (!Objects.equals(actualPorts.get(i).getContainerPort(), desiredPorts.get(i).getContainerPort())) return false;
                if (!Objects.equals(actualPorts.get(i).getProtocol(), desiredPorts.get(i).getProtocol())) return false;
            }
        }

        // Health check probes
        if (!Objects.equals(actualContainer.getLivenessProbe(), desiredContainer.getLivenessProbe())) return false;
        if (!Objects.equals(actualContainer.getReadinessProbe(), desiredContainer.getReadinessProbe())) return false;

        return true;
    }
}
