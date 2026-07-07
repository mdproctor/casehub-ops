package io.casehub.ops.app.k8s;

import java.util.Map;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class K8sNamespaceHandlerTest {

    KubernetesClient client;
    KubernetesMockServer server;
    private final K8sNamespaceHandler handler = new K8sNamespaceHandler();

    private K8sNamespaceSpec spec;

    @BeforeEach
    void setUp() {
        spec = new K8sNamespaceSpec("casehub", Labels.of(Map.of("managed-by", "casehub-ops")));
    }

    @Test
    void specType() {
        assertThat(handler.specType()).isEqualTo(K8sNamespaceSpec.class);
    }

    @Test
    void toResourceBuildsNamespace() {
        var ns = (Namespace) handler.toResource(spec);
        assertThat(ns.getMetadata().getName()).isEqualTo("casehub");
        assertThat(ns.getMetadata().getLabels()).containsEntry("managed-by", "casehub-ops");
    }

    @Test
    void applyCreatesNamespace() {
        handler.apply(client, spec);
        Namespace ns = client.namespaces().withName("casehub").get();
        assertThat(ns).isNotNull();
        assertThat(ns.getMetadata().getLabels()).containsEntry("managed-by", "casehub-ops");
    }

    @Test
    void readStatusAbsentWhenNotExists() {
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.ABSENT);
    }

    @Test
    void readStatusPresentWhenExists() {
        handler.apply(client, spec);
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void readStatusDriftedWhenLabelsChanged() {
        handler.apply(client, spec);
        Namespace ns = client.namespaces().withName("casehub").get();
        ns.getMetadata().getLabels().put("managed-by", "someone-else");
        client.namespaces().resource(ns).update();
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.DRIFTED);
    }

    @Test
    void deleteRemovesNamespace() {
        handler.apply(client, spec);
        handler.delete(client, spec);
        assertThat(client.namespaces().withName("casehub").get()).isNull();
    }

    @Test
    void applyIsIdempotent() {
        handler.apply(client, spec);
        handler.apply(client, spec);
        assertThat(handler.readStatus(client, spec)).isEqualTo(NodeStatus.PRESENT);
    }
}
