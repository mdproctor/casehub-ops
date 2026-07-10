package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ResourceRequirements;
import io.casehub.ops.app.model.FieldDrift;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class K8sDeploymentHandlerDiffTest {

    KubernetesClient client;
    KubernetesMockServer server;
    private final K8sDeploymentHandler handler = new K8sDeploymentHandler();

    @BeforeEach
    void setUp() {
        client.resource(new NamespaceBuilder()
                .withNewMetadata().withName("ns").endMetadata().build())
                .create();
    }

    @Test
    void noDriftReturnsEmptyList() {
        var spec = makeSpec("my-deploy", "nginx:1.25", 3);
        handler.apply(client, spec);

        List<FieldDrift> diffs = handler.readDiff(client, spec);
        assertThat(diffs).isEmpty();
    }

    @Test
    void replicasDriftReturnsFieldDrift() {
        var spec = makeSpec("my-deploy", "nginx:1.25", 3);
        handler.apply(client, spec);

        var actual = client.apps().deployments().inNamespace("ns").withName("my-deploy").get();
        var modified = new DeploymentBuilder(actual).editSpec().withReplicas(5).endSpec().build();
        client.resource(modified).update();

        List<FieldDrift> diffs = handler.readDiff(client, spec);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).fieldName()).isEqualTo("replicas");
        assertThat(diffs.get(0).expectedValue()).isEqualTo("3");
        assertThat(diffs.get(0).actualValue()).isEqualTo("5");
    }

    @Test
    void imageDriftReturnsFieldDrift() {
        var spec = makeSpec("my-deploy", "nginx:1.25", 2);
        handler.apply(client, spec);

        var actual = client.apps().deployments().inNamespace("ns").withName("my-deploy").get();
        var modified = new DeploymentBuilder(actual)
                .editSpec().editTemplate().editSpec()
                .editFirstContainer().withImage("nginx:1.24").endContainer()
                .endSpec().endTemplate().endSpec().build();
        client.resource(modified).update();

        List<FieldDrift> diffs = handler.readDiff(client, spec);
        assertThat(diffs).anyMatch(d -> d.fieldName().equals("image") && d.actualValue().equals("nginx:1.24"));
    }

    @Test
    void absentResourceReturnsEmptyList() {
        var spec = makeSpec("nonexistent", "nginx:1.25", 1);
        List<FieldDrift> diffs = handler.readDiff(client, spec);
        assertThat(diffs).isEmpty();
    }

    @Test
    void multipleFieldDriftsDetected() {
        var spec = makeSpec("my-deploy", "nginx:1.25", 3);
        handler.apply(client, spec);

        var actual = client.apps().deployments().inNamespace("ns").withName("my-deploy").get();
        var modified = new DeploymentBuilder(actual)
                .editSpec()
                    .withReplicas(5)
                    .editTemplate().editSpec()
                        .editFirstContainer().withImage("nginx:1.24").endContainer()
                    .endSpec().endTemplate()
                .endSpec().build();
        client.resource(modified).update();

        List<FieldDrift> diffs = handler.readDiff(client, spec);
        assertThat(diffs).hasSizeGreaterThanOrEqualTo(2);
        assertThat(diffs).anyMatch(d -> d.fieldName().equals("replicas"));
        assertThat(diffs).anyMatch(d -> d.fieldName().equals("image"));
    }

    private K8sDeploymentSpec makeSpec(String name, String image, int replicas) {
        return new K8sDeploymentSpec("ns", name, image, replicas,
                new ResourceRequirements("100m", "500m", "128Mi", "256Mi"),
                Labels.of(Map.of("app", name)),
                List.of(), Map.of(), Optional.empty());
    }
}
