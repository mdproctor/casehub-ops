package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.types.HealthCheckSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.PortMapping;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class K8sDeploymentHandlerTest {

    KubernetesClient client;
    KubernetesMockServer server;
    private final K8sDeploymentHandler handler = new K8sDeploymentHandler();

    private K8sDeploymentSpec spec;

    @BeforeEach
    void setUp() {
        client.resource(new NamespaceBuilder()
                .withNewMetadata().withName("casehub").endMetadata().build())
                .create();

        spec = new K8sDeploymentSpec(
                "casehub", "inventory", "quay.io/app:1.0", 2,
                new ResourceRequirements("500m", "1Gi", "250m", "512Mi"),
                Labels.of(Map.of("app", "inventory", "managed-by", "casehub-ops")),
                List.of(new PortMapping(8080, 80, "TCP")),
                Map.of("JAVA_OPTS", "-Xmx512m"),
                Optional.of(new HealthCheckSpec("/q/health", 8080, 5, 10)));
    }

    @Test
    void specType() {
        assertThat(handler.specType()).isEqualTo(K8sDeploymentSpec.class);
    }

    @Test
    void toResourceBuildsDeployment() {
        var dep = (Deployment) handler.toResource(spec);
        var container = dep.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertThat(dep.getMetadata().getName()).isEqualTo("inventory");
        assertThat(dep.getMetadata().getNamespace()).isEqualTo("casehub");
        assertThat(dep.getSpec().getReplicas()).isEqualTo(2);
        assertThat(container.getImage()).isEqualTo("quay.io/app:1.0");
        assertThat(container.getPorts()).hasSize(1);
        assertThat(container.getEnv()).anyMatch(e ->
                e.getName().equals("JAVA_OPTS") && e.getValue().equals("-Xmx512m"));
        assertThat(container.getLivenessProbe()).isNotNull();
        assertThat(container.getLivenessProbe().getHttpGet().getPath()).isEqualTo("/q/health");
    }

    @Test
    void applyCreatesDeployment() {
        handler.apply(client, spec);
        Deployment dep = client.apps().deployments()
                .inNamespace("casehub").withName("inventory").get();
        assertThat(dep).isNotNull();
        assertThat(dep.getSpec().getReplicas()).isEqualTo(2);
    }

    @Test
    void readStatusAbsent() {
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void readStatusPresent() {
        handler.apply(client, spec);
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void readStatusDriftedWhenImageChanged() {
        handler.apply(client, spec);
        Deployment dep = client.apps().deployments()
                .inNamespace("casehub").withName("inventory").get();
        dep.getSpec().getTemplate().getSpec().getContainers().get(0)
                .setImage("quay.io/app:2.0");
        client.apps().deployments().inNamespace("casehub").resource(dep).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void readStatusDriftedWhenReplicasChanged() {
        handler.apply(client, spec);
        Deployment dep = client.apps().deployments()
                .inNamespace("casehub").withName("inventory").get();
        dep.getSpec().setReplicas(5);
        client.apps().deployments().inNamespace("casehub").resource(dep).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void deleteRemovesDeployment() {
        handler.apply(client, spec);
        handler.delete(client, spec);
        assertThat(client.apps().deployments()
                .inNamespace("casehub").withName("inventory").get()).isNull();
    }
}
