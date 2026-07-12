package io.casehub.ops.app.k8s;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.StateEvent;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class K8sWatchManagerTest {

    KubernetesClient client;
    KubernetesMockServer server;

    private K8sWatchManager watchManager;
    private RecordingEventSource eventSource;
    private K8sClientRegistry clientRegistry;

    @BeforeEach
    void setUp() {
        eventSource = new RecordingEventSource();
        clientRegistry = new K8sClientRegistry(ref -> Map.of());
        clientRegistry.register("cluster-1", client.getConfiguration().getMasterUrl());
        watchManager = new K8sWatchManager(clientRegistry, eventSource);

        client.resource(new NamespaceBuilder()
                .withNewMetadata().withName("casehub").endMetadata().build()).create();
    }

    @AfterEach
    void tearDown() {
        watchManager.shutdown();
    }

    @Test
    void startWatchingIsIdempotent() {
        watchManager.startWatching("cluster-1", "casehub");
        watchManager.startWatching("cluster-1", "casehub");
        assertThat(watchManager.activeWatchCount()).isEqualTo(1);
    }

    @Test
    void isWatchingReportsState() {
        assertThat(watchManager.isWatching("cluster-1", "casehub")).isFalse();
        watchManager.startWatching("cluster-1", "casehub");
        assertThat(watchManager.isWatching("cluster-1", "casehub")).isTrue();
    }

    @Test
    void stopWatchingRemovesCluster() {
        watchManager.startWatching("cluster-1", "casehub");
        watchManager.stopWatching("cluster-1");
        assertThat(watchManager.isWatching("cluster-1", "casehub")).isFalse();
        assertThat(watchManager.activeWatchCount()).isEqualTo(0);
    }

    @Test
    void shutdownClearsAllWatches() {
        watchManager.startWatching("cluster-1", "casehub");
        watchManager.shutdown();
        assertThat(watchManager.activeWatchCount()).isEqualTo(0);
    }

    @Test
    void emitsDriftedOnDeploymentModification() throws Exception {
        watchManager.startWatching("cluster-1", "casehub");
        Thread.sleep(200);

        var deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName("inventory")
                    .withNamespace("casehub")
                    .withLabels(Map.of("managed-by", "casehub-ops", "app", "inventory"))
                .endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withNewSelector().withMatchLabels(Map.of("app", "inventory")).endSelector()
                    .withNewTemplate()
                        .withNewMetadata().withLabels(Map.of("app", "inventory")).endMetadata()
                        .withNewSpec()
                            .addNewContainer().withName("app").withImage("img:1").endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
        client.apps().deployments().inNamespace("casehub").resource(deployment).create();
        Thread.sleep(200);

        deployment.getSpec().setReplicas(3);
        client.apps().deployments().inNamespace("casehub").resource(deployment).update();
        Thread.sleep(500);

        assertThat(eventSource.events).anySatisfy(e -> {
            assertThat(e.node()).isEqualTo(NodeId.of("cluster-1:inventory:deployment"));
            assertThat(e.newStatus()).isEqualTo(NodeStatus.DRIFTED);
        });
    }

    @Test
    void emitsAbsentOnDeploymentDeletion() throws Exception {
        var deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName("web")
                    .withNamespace("casehub")
                    .withLabels(Map.of("managed-by", "casehub-ops", "app", "web"))
                .endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withNewSelector().withMatchLabels(Map.of("app", "web")).endSelector()
                    .withNewTemplate()
                        .withNewMetadata().withLabels(Map.of("app", "web")).endMetadata()
                        .withNewSpec()
                            .addNewContainer().withName("app").withImage("img:1").endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
        client.apps().deployments().inNamespace("casehub").resource(deployment).create();

        watchManager.startWatching("cluster-1", "casehub");
        Thread.sleep(200);

        client.apps().deployments().inNamespace("casehub").withName("web").delete();
        Thread.sleep(500);

        assertThat(eventSource.events).anySatisfy(e -> {
            assertThat(e.node()).isEqualTo(NodeId.of("cluster-1:web:deployment"));
            assertThat(e.newStatus()).isEqualTo(NodeStatus.ABSENT);
        });
    }

    @Test
    void watchesServicesAndConfigMaps() throws Exception {
        watchManager.startWatching("cluster-1", "casehub");
        Thread.sleep(200);

        client.services().inNamespace("casehub").resource(new ServiceBuilder()
                .withNewMetadata()
                    .withName("api-svc")
                    .withNamespace("casehub")
                    .withLabels(Map.of("managed-by", "casehub-ops"))
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                .endSpec()
                .build()).create();

        client.configMaps().inNamespace("casehub").resource(new ConfigMapBuilder()
                .withNewMetadata()
                    .withName("app-config")
                    .withNamespace("casehub")
                    .withLabels(Map.of("managed-by", "casehub-ops"))
                .endMetadata()
                .withData(Map.of("key", "value"))
                .build()).create();
        Thread.sleep(200);

        client.configMaps().inNamespace("casehub").withName("app-config").delete();
        Thread.sleep(500);

        assertThat(eventSource.events).anySatisfy(e -> {
            assertThat(e.node()).isEqualTo(NodeId.of("cluster-1:app-config:configmap"));
            assertThat(e.newStatus()).isEqualTo(NodeStatus.ABSENT);
        });
    }

    @Test
    void ignoresResourcesWithoutManagedByLabel() throws Exception {
        watchManager.startWatching("cluster-1", "casehub");
        Thread.sleep(200);

        client.configMaps().inNamespace("casehub").resource(new ConfigMapBuilder()
                .withNewMetadata()
                    .withName("unmanaged")
                    .withNamespace("casehub")
                .endMetadata()
                .withData(Map.of("key", "value"))
                .build()).create();
        Thread.sleep(500);

        assertThat(eventSource.events).noneMatch(
                e -> e.node().value().contains("unmanaged"));
    }

    @Test
    void driftWatcherMapsActionsCorrectly() {
        var watcher = watchManager.<io.fabric8.kubernetes.api.model.apps.Deployment>driftWatcher("c1", "deployment");

        var deployment = new DeploymentBuilder()
                .withNewMetadata().withName("test").withNamespace("ns").endMetadata()
                .build();

        watcher.eventReceived(io.fabric8.kubernetes.client.Watcher.Action.ADDED, deployment);
        assertThat(eventSource.events).isEmpty();

        watcher.eventReceived(io.fabric8.kubernetes.client.Watcher.Action.MODIFIED, deployment);
        assertThat(eventSource.events).hasSize(1);
        assertThat(eventSource.events.get(0).node()).isEqualTo(NodeId.of("c1:test:deployment"));
        assertThat(eventSource.events.get(0).newStatus()).isEqualTo(NodeStatus.DRIFTED);

        watcher.eventReceived(io.fabric8.kubernetes.client.Watcher.Action.DELETED, deployment);
        assertThat(eventSource.events).hasSize(2);
        assertThat(eventSource.events.get(1).newStatus()).isEqualTo(NodeStatus.ABSENT);
    }

    static class RecordingEventSource extends KubernetesEventSource {
        final CopyOnWriteArrayList<StateEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(StateEvent event) {
            events.add(event);
            super.emit(event);
        }
    }
}
