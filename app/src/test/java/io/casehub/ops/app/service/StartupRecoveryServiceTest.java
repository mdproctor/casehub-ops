package io.casehub.ops.app.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.entity.ClusterReferenceEntity;
import io.casehub.ops.app.goal.ApplicationGoalCompiler;
import io.casehub.ops.app.model.ApplicationStatus;
import io.casehub.ops.app.model.ClusterType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Plain unit tests for StartupRecoveryService.
 *
 * <p>Uses recording stubs for ReconciliationLoop, K8sClientRegistry,
 * DecommissionCompletionHandler, and ApplicationLifecycleService to verify
 * correct recovery behavior at startup. Entity queries are provided via
 * functional interfaces (Suppliers) to avoid Panache/CDI dependency.
 */
class StartupRecoveryServiceTest {

    private StartupRecoveryService service;
    private ApplicationLifecycleService lifecycleService;
    private RecordingReconciliationLoop reconciliationLoop;
    private RecordingClientRegistry clientRegistry;
    private RecordingDecommissionHandler decommissionHandler;
    private ApplicationGoalCompiler goalCompiler;
    private DefaultDesiredStateGraphFactory graphFactory;

    // Test data holders — mutated by tests before calling recover()
    private List<ClusterReferenceEntity> allClusters;
    private List<ApplicationEntity> activeApps;
    private Map<String, List<ClusterReferenceEntity>> clustersByTenancy;

    @BeforeEach
    void setUp() {
        lifecycleService = new ApplicationLifecycleService();
        reconciliationLoop = new RecordingReconciliationLoop();
        clientRegistry = new RecordingClientRegistry();
        decommissionHandler = new RecordingDecommissionHandler();
        goalCompiler = new ApplicationGoalCompiler();
        graphFactory = new DefaultDesiredStateGraphFactory();
        allClusters = new ArrayList<>();
        activeApps = new ArrayList<>();
        clustersByTenancy = new java.util.HashMap<>();

        service = new StartupRecoveryService(
                reconciliationLoop::start,
                goalCompiler,
                graphFactory,
                clientRegistry::register,
                lifecycleService,
                decommissionHandler::registerDecommission,
                () -> allClusters,
                () -> activeApps,
                tenancyId -> clustersByTenancy.getOrDefault(tenancyId, List.of()));
    }

    @Test
    void registersAllClustersInClientRegistry() {
        var cluster1 = makeCluster("cluster-1", "https://k8s-1:6443", "default", "tenant-a");
        var cluster2 = makeCluster("cluster-2", "https://k8s-2:6443", "prod", "tenant-b");
        allClusters.addAll(List.of(cluster1, cluster2));

        service.recover();

        assertThat(clientRegistry.registered).containsExactlyInAnyOrder(
                cluster1.id.toString() + "=" + "https://k8s-1:6443",
                cluster2.id.toString() + "=" + "https://k8s-2:6443");
    }

    @Test
    void recoversRunningApplication() {
        var cluster = makeCluster("c1", "https://k8s:6443", "default", "tenant-1");
        allClusters.add(cluster);
        clustersByTenancy.put("tenant-1", List.of(cluster));

        var app = makeApp("my-app", "tenant-1", ApplicationStatus.RUNNING, "[]");
        activeApps.add(app);

        service.recover();

        String expectedKey = "tenant-1:" + app.id + ":" + cluster.id;
        assertThat(reconciliationLoop.started).containsKey(expectedKey);
        assertThat(lifecycleService.hasActiveLoopsForCluster(cluster.id.toString())).isTrue();
    }

    @Test
    void recoversDeployingApplication() {
        var cluster = makeCluster("c1", "https://k8s:6443", "default", "tenant-1");
        allClusters.add(cluster);
        clustersByTenancy.put("tenant-1", List.of(cluster));

        var app = makeApp("deploying-app", "tenant-1", ApplicationStatus.DEPLOYING, "[]");
        activeApps.add(app);

        service.recover();

        String expectedKey = "tenant-1:" + app.id + ":" + cluster.id;
        assertThat(reconciliationLoop.started).containsKey(expectedKey);
    }

    @Test
    void recoversDegradedApplication() {
        var cluster = makeCluster("c1", "https://k8s:6443", "default", "tenant-1");
        allClusters.add(cluster);
        clustersByTenancy.put("tenant-1", List.of(cluster));

        var app = makeApp("degraded-app", "tenant-1", ApplicationStatus.DEGRADED, "[]");
        activeApps.add(app);

        service.recover();

        String expectedKey = "tenant-1:" + app.id + ":" + cluster.id;
        assertThat(reconciliationLoop.started).containsKey(expectedKey);
    }

    @Test
    void decommissioningAppGetsEmptyGraphAndRegistersHandler() {
        var cluster = makeCluster("c1", "https://k8s:6443", "default", "tenant-1");
        allClusters.add(cluster);
        clustersByTenancy.put("tenant-1", List.of(cluster));

        var app = makeApp("decomm-app", "tenant-1", ApplicationStatus.DECOMMISSIONING, "[]");
        activeApps.add(app);

        service.recover();

        String expectedKey = "tenant-1:" + app.id + ":" + cluster.id;
        assertThat(reconciliationLoop.started).containsKey(expectedKey);

        // Verify the graph is empty (no nodes)
        DesiredStateGraph graph = reconciliationLoop.started.get(expectedKey);
        assertThat(graph.nodes()).isEmpty();

        // Verify decommission handler was registered
        assertThat(decommissionHandler.registeredApps).containsKey(app.id);
        assertThat(decommissionHandler.registeredApps.get(app.id)).contains(expectedKey);
    }

    @Test
    void recoversMultiClusterApplication() {
        var cluster1 = makeCluster("c1", "https://k8s-1:6443", "default", "tenant-1");
        var cluster2 = makeCluster("c2", "https://k8s-2:6443", "prod", "tenant-1");
        allClusters.addAll(List.of(cluster1, cluster2));
        clustersByTenancy.put("tenant-1", List.of(cluster1, cluster2));

        var app = makeApp("multi-cluster", "tenant-1", ApplicationStatus.RUNNING, "[]");
        activeApps.add(app);

        service.recover();

        String key1 = "tenant-1:" + app.id + ":" + cluster1.id;
        String key2 = "tenant-1:" + app.id + ":" + cluster2.id;
        assertThat(reconciliationLoop.started).containsKeys(key1, key2);
        assertThat(lifecycleService.hasActiveLoopsForCluster(cluster1.id.toString())).isTrue();
        assertThat(lifecycleService.hasActiveLoopsForCluster(cluster2.id.toString())).isTrue();
    }

    @Test
    void decommissioningMultiClusterRegistersAllKeys() {
        var cluster1 = makeCluster("c1", "https://k8s-1:6443", "default", "tenant-1");
        var cluster2 = makeCluster("c2", "https://k8s-2:6443", "prod", "tenant-1");
        allClusters.addAll(List.of(cluster1, cluster2));
        clustersByTenancy.put("tenant-1", List.of(cluster1, cluster2));

        var app = makeApp("decomm-multi", "tenant-1", ApplicationStatus.DECOMMISSIONING, "[]");
        activeApps.add(app);

        service.recover();

        String key1 = "tenant-1:" + app.id + ":" + cluster1.id;
        String key2 = "tenant-1:" + app.id + ":" + cluster2.id;
        assertThat(decommissionHandler.registeredApps.get(app.id))
                .containsExactlyInAnyOrder(key1, key2);
    }

    @Test
    void noActiveAppsStartsNoLoops() {
        var cluster = makeCluster("c1", "https://k8s:6443", "default", "tenant-1");
        allClusters.add(cluster);

        service.recover();

        assertThat(reconciliationLoop.started).isEmpty();
        assertThat(clientRegistry.registered).hasSize(1); // cluster still registered
    }

    @Test
    void noClustersRegistersNothingAndStartsNoLoops() {
        service.recover();

        assertThat(clientRegistry.registered).isEmpty();
        assertThat(reconciliationLoop.started).isEmpty();
    }

    @Test
    void multiTenantRecovery() {
        var clusterA = makeCluster("cA", "https://k8s-a:6443", "ns-a", "tenant-a");
        var clusterB = makeCluster("cB", "https://k8s-b:6443", "ns-b", "tenant-b");
        allClusters.addAll(List.of(clusterA, clusterB));
        clustersByTenancy.put("tenant-a", List.of(clusterA));
        clustersByTenancy.put("tenant-b", List.of(clusterB));

        var appA = makeApp("app-a", "tenant-a", ApplicationStatus.RUNNING, "[]");
        var appB = makeApp("app-b", "tenant-b", ApplicationStatus.DEPLOYING, "[]");
        activeApps.addAll(List.of(appA, appB));

        service.recover();

        String keyA = "tenant-a:" + appA.id + ":" + clusterA.id;
        String keyB = "tenant-b:" + appB.id + ":" + clusterB.id;
        assertThat(reconciliationLoop.started).containsKeys(keyA, keyB);
    }

    @Test
    void nonDecommissioningAppsDoNotRegisterWithDecommissionHandler() {
        var cluster = makeCluster("c1", "https://k8s:6443", "default", "tenant-1");
        allClusters.add(cluster);
        clustersByTenancy.put("tenant-1", List.of(cluster));

        var app = makeApp("running-app", "tenant-1", ApplicationStatus.RUNNING, "[]");
        activeApps.add(app);

        service.recover();

        assertThat(decommissionHandler.registeredApps).isEmpty();
    }

    @Test
    void appWithNoClustersStartsNoLoops() {
        var cluster = makeCluster("c1", "https://k8s:6443", "default", "other-tenant");
        allClusters.add(cluster);
        // clustersByTenancy has no entry for "tenant-1"

        var app = makeApp("orphan-app", "tenant-1", ApplicationStatus.RUNNING, "[]");
        activeApps.add(app);

        service.recover();

        assertThat(reconciliationLoop.started).isEmpty();
    }

    // --- Helper methods ---

    private ClusterReferenceEntity makeCluster(String name, String apiUrl,
                                                String namespace, String tenancyId) {
        var cluster = new ClusterReferenceEntity();
        cluster.id = UUID.randomUUID();
        cluster.name = name;
        cluster.apiUrl = apiUrl;
        cluster.namespace = namespace;
        cluster.clusterType = ClusterType.KUBERNETES;
        cluster.tenancyId = tenancyId;
        return cluster;
    }

    private ApplicationEntity makeApp(String name, String tenancyId,
                                       ApplicationStatus status, String servicesJson) {
        var app = new ApplicationEntity();
        app.id = UUID.randomUUID();
        app.name = name;
        app.tenancyId = tenancyId;
        app.status = status;
        app.servicesJson = servicesJson;
        return app;
    }

    // --- Recording stubs ---

    private static class RecordingReconciliationLoop {
        final Map<String, DesiredStateGraph> started = new java.util.LinkedHashMap<>();

        void start(String key, DesiredStateGraph graph) {
            if (started.containsKey(key)) {
                throw new IllegalStateException("Loop already running for key: " + key);
            }
            started.put(key, graph);
        }
    }

    private static class RecordingClientRegistry {
        final List<String> registered = new ArrayList<>();

        void register(String clusterId, String apiUrl, String credentialRef, boolean trustCerts) {
            registered.add(clusterId + "=" + apiUrl);
        }
    }

    private static class RecordingDecommissionHandler {
        final Map<UUID, Set<String>> registeredApps = new java.util.LinkedHashMap<>();

        void registerDecommission(UUID appId, Set<String> compositeKeys) {
            registeredApps.put(appId, new HashSet<>(compositeKeys));
        }
    }
}
