package io.casehub.ops.app.k8s;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sServiceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.ops.api.infra.types.ServiceType;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class K8sServiceHandlerTest {

    KubernetesClient client;
    KubernetesMockServer server;
    private final K8sServiceHandler handler = new K8sServiceHandler();

    private K8sServiceSpec spec;

    @BeforeEach
    void setUp() {
        client.resource(new NamespaceBuilder()
                                .withNewMetadata().withName("casehub").endMetadata().build())
              .create();

        spec = new K8sServiceSpec(
                "casehub", "inventory-svc", 80, 8080,
                ServiceType.CLUSTER_IP,
                Labels.of(Map.of("app", "inventory", "managed-by", "casehub-ops")),
                Labels.of(Map.of("app", "inventory")));
    }

    @Test
    void specType() {
        assertThat(handler.specType()).isEqualTo(K8sServiceSpec.class);
    }

    @Test
    void toResourceBuildsService() {
        var svc = (Service) handler.toResource(spec);
        assertThat(svc.getMetadata().getName()).isEqualTo("inventory-svc");
        assertThat(svc.getMetadata().getNamespace()).isEqualTo("casehub");
        assertThat(svc.getSpec().getType()).isEqualTo("ClusterIP");
        assertThat(svc.getSpec().getPorts()).hasSize(1);
        assertThat(svc.getSpec().getPorts().get(0).getPort()).isEqualTo(80);
        assertThat(svc.getSpec().getPorts().get(0).getTargetPort().getIntVal()).isEqualTo(8080);
        assertThat(svc.getSpec().getSelector()).containsEntry("app", "inventory");
    }

    @Test
    void selectorUsesOnlyAppLabel() {
        var svc = (Service) handler.toResource(spec);
        assertThat(svc.getMetadata().getLabels()).containsEntry("managed-by", "casehub-ops");
        assertThat(svc.getSpec().getSelector()).doesNotContainKey("managed-by");
        assertThat(svc.getSpec().getSelector()).containsExactlyEntriesOf(Map.of("app", "inventory"));
    }


    @Test
    void applyCreatesService() {
        handler.apply(client, spec);
        Service svc = client.services()
                .inNamespace("casehub").withName("inventory-svc").get();
        assertThat(svc).isNotNull();
        assertThat(svc.getSpec().getType()).isEqualTo("ClusterIP");
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
    void readStatusDriftedWhenPortChanged() {
        handler.apply(client, spec);
        Service svc = client.services()
                .inNamespace("casehub").withName("inventory-svc").get();
        svc.getSpec().getPorts().get(0).setPort(9090);
        client.services().inNamespace("casehub").resource(svc).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void readStatusDriftedWhenTypeChanged() {
        handler.apply(client, spec);
        Service svc = client.services()
                .inNamespace("casehub").withName("inventory-svc").get();
        svc.getSpec().setType("NodePort");
        client.services().inNamespace("casehub").resource(svc).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void deleteRemovesService() {
        handler.apply(client, spec);
        handler.delete(client, spec);
        assertThat(client.services()
                .inNamespace("casehub").withName("inventory-svc").get()).isNull();
    }

    @Test
    void applyIsIdempotent() {
        handler.apply(client, spec);
        handler.apply(client, spec);
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.PRESENT);
    }
}
