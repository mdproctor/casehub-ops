package io.casehub.ops.app.k8s;

import java.util.ArrayList;
import java.util.List;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.app.model.FieldDrift;
import io.fabric8.kubernetes.api.model.ContainerPort;
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

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
            var actual = client.apps().deployments()
                               .inNamespace(spec.namespace()).withName(spec.name()).get();
            if (actual == null) {return NodeStatus.ABSENT;}
            return readDiff(client, spec).isEmpty() ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
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

    @Override
    public List<FieldDrift> readDiff(KubernetesClient client, K8sDeploymentSpec spec) {
        try {
            var actual = client.apps().deployments()
                               .inNamespace(spec.namespace()).withName(spec.name()).get();
            if (actual == null) {return List.of();}

            var desired = (Deployment) toResource(spec);
            return computeDiffs(actual, desired);
        } catch (KubernetesClientException e) {
            return List.of();
        }
    }

    private List<FieldDrift> computeDiffs(Deployment actual, Deployment desired) {
        var diffs = new ArrayList<FieldDrift>();
        var ac    = actual.getSpec().getTemplate().getSpec().getContainers().get(0);
        var dc    = desired.getSpec().getTemplate().getSpec().getContainers().get(0);

        if (!Objects.equals(actual.getSpec().getReplicas(), desired.getSpec().getReplicas())) {
            diffs.add(new FieldDrift("replicas",
                                     String.valueOf(desired.getSpec().getReplicas()),
                                     String.valueOf(actual.getSpec().getReplicas())));
        }
        if (!Objects.equals(ac.getImage(), dc.getImage())) {
            diffs.add(new FieldDrift("image", dc.getImage(), ac.getImage()));
        }

        Map<String, String> actualEnv = ac.getEnv() == null ? Map.of()
                                                            : ac.getEnv().stream().collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
        Map<String, String> desiredEnv = dc.getEnv() == null ? Map.of()
                                                             : dc.getEnv().stream().collect(Collectors.toMap(EnvVar::getName, EnvVar::getValue));
        if (!actualEnv.equals(desiredEnv)) {
            diffs.add(new FieldDrift("env", desiredEnv.toString(), actualEnv.toString()));
        }

        if (!Objects.equals(ac.getResources().getRequests(), dc.getResources().getRequests())) {
            diffs.add(new FieldDrift("resourceRequests",
                                     String.valueOf(dc.getResources().getRequests()),
                                     String.valueOf(ac.getResources().getRequests())));
        }
        if (!Objects.equals(ac.getResources().getLimits(), dc.getResources().getLimits())) {
            diffs.add(new FieldDrift("resourceLimits",
                                     String.valueOf(dc.getResources().getLimits()),
                                     String.valueOf(ac.getResources().getLimits())));
        }

        if (!portsMatch(ac.getPorts(), dc.getPorts())) {
            diffs.add(new FieldDrift("ports",
                                     String.valueOf(dc.getPorts()), String.valueOf(ac.getPorts())));
        }

        if (!Objects.equals(ac.getLivenessProbe(), dc.getLivenessProbe())) {
            diffs.add(new FieldDrift("livenessProbe",
                                     String.valueOf(dc.getLivenessProbe()), String.valueOf(ac.getLivenessProbe())));
        }
        if (!Objects.equals(ac.getReadinessProbe(), dc.getReadinessProbe())) {
            diffs.add(new FieldDrift("readinessProbe",
                                     String.valueOf(dc.getReadinessProbe()), String.valueOf(ac.getReadinessProbe())));
        }

        return List.copyOf(diffs);
    }

    private boolean portsMatch(List<?> actual, List<?> desired) {
        if (actual == null && desired == null) {return true;}
        if (actual == null || desired == null) {return false;}
        if (actual.size() != desired.size()) {return false;}
        for (int i = 0; i < actual.size(); i++) {
            var ap = (ContainerPort) actual.get(i);
            var dp = (ContainerPort) desired.get(i);
            if (!Objects.equals(ap.getContainerPort(), dp.getContainerPort())) {return false;}
            if (!Objects.equals(ap.getProtocol(), dp.getProtocol())) {return false;}
        }
        return true;
    }
}
