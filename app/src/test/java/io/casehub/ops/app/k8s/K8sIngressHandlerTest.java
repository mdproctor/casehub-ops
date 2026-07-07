package io.casehub.ops.app.k8s;

import java.util.List;
import java.util.Map;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sIngressSpec;
import io.casehub.ops.api.infra.types.IngressRule;
import io.casehub.ops.api.infra.types.Labels;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class K8sIngressHandlerTest {

    KubernetesClient client;
    KubernetesMockServer server;
    private final K8sIngressHandler handler = new K8sIngressHandler();

    private K8sIngressSpec spec;

    @BeforeEach
    void setUp() {
        client.resource(new NamespaceBuilder()
                .withNewMetadata().withName("casehub").endMetadata().build())
                .create();

        spec = new K8sIngressSpec(
                "casehub", "inventory-ingress", "inventory.casehub.io",
                List.of(new IngressRule("/api", "inventory-svc", 80)),
                Labels.of(Map.of("app", "inventory", "managed-by", "casehub-ops")));
    }

    @Test
    void specType() {
        assertThat(handler.specType()).isEqualTo(K8sIngressSpec.class);
    }

    @Test
    void toResourceBuildsIngress() {
        var ingress = (Ingress) handler.toResource(spec);
        assertThat(ingress.getMetadata().getName()).isEqualTo("inventory-ingress");
        assertThat(ingress.getMetadata().getNamespace()).isEqualTo("casehub");
        var rules = ingress.getSpec().getRules();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getHost()).isEqualTo("inventory.casehub.io");
        var paths = rules.get(0).getHttp().getPaths();
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).getPath()).isEqualTo("/api");
        assertThat(paths.get(0).getBackend().getService().getName()).isEqualTo("inventory-svc");
        assertThat(paths.get(0).getBackend().getService().getPort().getNumber()).isEqualTo(80);
    }

    @Test
    void applyCreatesIngress() {
        handler.apply(client, spec);
        Ingress ingress = client.network().v1().ingresses()
                .inNamespace("casehub").withName("inventory-ingress").get();
        assertThat(ingress).isNotNull();
        assertThat(ingress.getSpec().getRules().get(0).getHost())
                .isEqualTo("inventory.casehub.io");
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
    void readStatusDriftedWhenHostChanged() {
        handler.apply(client, spec);
        Ingress ingress = client.network().v1().ingresses()
                .inNamespace("casehub").withName("inventory-ingress").get();
        ingress.getSpec().getRules().get(0).setHost("other.casehub.io");
        client.network().v1().ingresses().inNamespace("casehub").resource(ingress).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void readStatusDriftedWhenPathChanged() {
        handler.apply(client, spec);
        Ingress ingress = client.network().v1().ingresses()
                .inNamespace("casehub").withName("inventory-ingress").get();
        ingress.getSpec().getRules().get(0).getHttp().getPaths().get(0).setPath("/changed");
        client.network().v1().ingresses().inNamespace("casehub").resource(ingress).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void deleteRemovesIngress() {
        handler.apply(client, spec);
        handler.delete(client, spec);
        assertThat(client.network().v1().ingresses()
                .inNamespace("casehub").withName("inventory-ingress").get()).isNull();
    }

    @Test
    void applyIsIdempotent() {
        handler.apply(client, spec);
        handler.apply(client, spec);
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.PRESENT);
    }
}
