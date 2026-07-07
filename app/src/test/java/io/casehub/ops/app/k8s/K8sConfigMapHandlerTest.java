package io.casehub.ops.app.k8s;

import java.util.Map;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sConfigMapSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class K8sConfigMapHandlerTest {

    KubernetesClient client;
    KubernetesMockServer server;
    private final K8sConfigMapHandler handler = new K8sConfigMapHandler();

    private K8sConfigMapSpec spec;

    @BeforeEach
    void setUp() {
        client.resource(new NamespaceBuilder()
                .withNewMetadata().withName("casehub").endMetadata().build())
                .create();

        spec = new K8sConfigMapSpec(
                "casehub", "inventory-config",
                Map.of("application.properties", "quarkus.http.port=8080",
                       "logging.properties", "level=INFO"),
                Labels.of(Map.of("app", "inventory", "managed-by", "casehub-ops")));
    }

    @Test
    void specType() {
        assertThat(handler.specType()).isEqualTo(K8sConfigMapSpec.class);
    }

    @Test
    void toResourceBuildsConfigMap() {
        var cm = (ConfigMap) handler.toResource(spec);
        assertThat(cm.getMetadata().getName()).isEqualTo("inventory-config");
        assertThat(cm.getMetadata().getNamespace()).isEqualTo("casehub");
        assertThat(cm.getData()).containsEntry("application.properties", "quarkus.http.port=8080");
        assertThat(cm.getData()).containsEntry("logging.properties", "level=INFO");
        assertThat(cm.getMetadata().getLabels()).containsEntry("managed-by", "casehub-ops");
    }

    @Test
    void applyCreatesConfigMap() {
        handler.apply(client, spec);
        ConfigMap cm = client.configMaps()
                .inNamespace("casehub").withName("inventory-config").get();
        assertThat(cm).isNotNull();
        assertThat(cm.getData()).containsEntry("application.properties", "quarkus.http.port=8080");
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
    void readStatusDriftedWhenDataChanged() {
        handler.apply(client, spec);
        ConfigMap cm = client.configMaps()
                .inNamespace("casehub").withName("inventory-config").get();
        cm.getData().put("application.properties", "quarkus.http.port=9090");
        client.configMaps().inNamespace("casehub").resource(cm).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void readStatusDriftedWhenKeyRemoved() {
        handler.apply(client, spec);
        ConfigMap cm = client.configMaps()
                .inNamespace("casehub").withName("inventory-config").get();
        cm.getData().remove("logging.properties");
        client.configMaps().inNamespace("casehub").resource(cm).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void deleteRemovesConfigMap() {
        handler.apply(client, spec);
        handler.delete(client, spec);
        assertThat(client.configMaps()
                .inNamespace("casehub").withName("inventory-config").get()).isNull();
    }

    @Test
    void applyIsIdempotent() {
        handler.apply(client, spec);
        handler.apply(client, spec);
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.PRESENT);
    }
}
