package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.entity.ClusterReferenceEntity;
import io.casehub.ops.app.goal.ApplicationGoalCompiler;
import io.casehub.ops.app.k8s.K8sClientRegistry;
import io.casehub.ops.app.model.ApplicationStatus;
import io.casehub.ops.app.model.ServiceDefinition;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recovers active reconciliation state on application startup.
 *
 * <p>On startup:
 * <ol>
 *   <li>Registers all known clusters in {@link K8sClientRegistry}</li>
 *   <li>Finds non-terminal applications (DEPLOYING, RUNNING, DEGRADED, DECOMMISSIONING)</li>
 *   <li>For each app x cluster: compiles the goal, starts the reconciliation loop,
 *       tracks the loop key in {@link ApplicationLifecycleService}</li>
 *   <li>DECOMMISSIONING apps get empty graphs and re-register with
 *       {@link DecommissionCompletionHandler}</li>
 * </ol>
 *
 * <p>The {@link #onStartup} method delegates to {@link #recover()} for testability.
 */
@ApplicationScoped
public class StartupRecoveryService {

    private static final Logger LOG = Logger.getLogger(StartupRecoveryService.class.getName());

    @FunctionalInterface
    public interface ClusterRegistrar {
        void register(String clusterId, String apiUrl, String credentialRef, boolean trustCerts);
    }

    private final BiConsumer<String, DesiredStateGraph> loopStarter;
    private final ClusterRegistrar clusterRegistrar;
    private final ApplicationGoalCompiler goalCompiler;
    private final DesiredStateGraphFactory graphFactory;
    private final BiConsumer<String, String> loopKeyTracker;
    private final BiConsumer<UUID, Set<String>> decommissionRegistrar;

    private final Supplier<List<ClusterReferenceEntity>> allClustersSupplier;
    private final Supplier<List<ApplicationEntity>> activeAppsSupplier;
    private final Function<String, List<ClusterReferenceEntity>> clustersByTenancyLookup;

    @Inject
    ObjectMapper objectMapper;

    /**
     * CDI constructor — wires to real CDI beans.
     */
    @Inject
    public StartupRecoveryService(ReconciliationLoop reconciliationLoop,
                                   ApplicationGoalCompiler goalCompiler,
                                   DesiredStateGraphFactory graphFactory,
                                   K8sClientRegistry clientRegistry,
                                   ApplicationLifecycleService lifecycleService,
                                   DecommissionCompletionHandler decommissionHandler) {
        this.loopStarter = reconciliationLoop::start;
        this.clusterRegistrar = clientRegistry::register;  // 4-arg register(id, url, cred, trust)
        this.goalCompiler = goalCompiler;
        this.graphFactory = graphFactory;
        this.loopKeyTracker = lifecycleService::trackLoopKey;
        this.decommissionRegistrar = decommissionHandler::registerDecommission;

        this.allClustersSupplier = ClusterReferenceEntity::listAll;
        this.activeAppsSupplier = () -> ApplicationEntity.list(
                "status in (?1)",
                List.of(ApplicationStatus.DEPLOYING, ApplicationStatus.RUNNING,
                        ApplicationStatus.DEGRADED, ApplicationStatus.DECOMMISSIONING));
        this.clustersByTenancyLookup = ClusterReferenceEntity::findByTenancyId;
    }

    /**
     * Test constructor — all dependencies provided as functional interfaces.
     */
    StartupRecoveryService(BiConsumer<String, DesiredStateGraph> loopStarter,
                            ApplicationGoalCompiler goalCompiler,
                            DesiredStateGraphFactory graphFactory,
                            ClusterRegistrar clusterRegistrar,
                            ApplicationLifecycleService lifecycleService,
                            BiConsumer<UUID, Set<String>> decommissionRegistrar,
                            Supplier<List<ClusterReferenceEntity>> allClustersSupplier,
                            Supplier<List<ApplicationEntity>> activeAppsSupplier,
                            Function<String, List<ClusterReferenceEntity>> clustersByTenancyLookup) {
        this.loopStarter = loopStarter;
        this.clusterRegistrar = clusterRegistrar;
        this.goalCompiler = goalCompiler;
        this.graphFactory = graphFactory;
        this.loopKeyTracker = lifecycleService::trackLoopKey;
        this.decommissionRegistrar = decommissionRegistrar;

        this.allClustersSupplier = allClustersSupplier;
        this.activeAppsSupplier = activeAppsSupplier;
        this.clustersByTenancyLookup = clustersByTenancyLookup;
    }

    void onStartup(@Observes @Priority(20) StartupEvent event) {
        recover();
    }

    /**
     * Recovers all active reconciliation loops. Separated from {@link #onStartup}
     * for direct invocation in tests.
     */
    public void recover() {
        // 1. Register all known clusters in K8sClientRegistry
        List<ClusterReferenceEntity> clusters = allClustersSupplier.get();
        for (var cluster : clusters) {
            clusterRegistrar.register(cluster.id.toString(), cluster.apiUrl, cluster.credentialRef, cluster.trustCerts);
        }
        LOG.info("Registered " + clusters.size() + " clusters in K8sClientRegistry");

        // 2. Find non-terminal applications
        List<ApplicationEntity> activeApps = activeAppsSupplier.get();
        LOG.info("Found " + activeApps.size() + " non-terminal applications to recover");

        // 3. Restart reconciliation loops and populate active loop index
        for (var app : activeApps) {
            recoverApplication(app);
        }
    }

    private void recoverApplication(ApplicationEntity app) {
        List<ClusterReferenceEntity> appClusters = clustersByTenancyLookup.apply(app.tenancyId);
        boolean decommissioning = app.status == ApplicationStatus.DECOMMISSIONING;
        Set<String> compositeKeys = new HashSet<>();

        for (var cluster : appClusters) {
            String key = app.tenancyId + ":" + app.id + ":" + cluster.id;

            DesiredStateGraph graph;
            if (decommissioning) {
                graph = graphFactory.of(List.of(), List.of());
            } else {
                List<ServiceDefinition> services = parseServices(app.servicesJson);
                graph = goalCompiler.compileForCluster(
                        services, cluster.id.toString(), cluster.namespace, graphFactory);
            }

            try {
                loopStarter.accept(key, graph);
                loopKeyTracker.accept(cluster.id.toString(), key);
                compositeKeys.add(key);
                LOG.fine(() -> "Recovered loop for " + key);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to recover loop for " + key, e);
            }
        }

        if (decommissioning && !compositeKeys.isEmpty()) {
            decommissionRegistrar.accept(app.id, compositeKeys);
        }
    }

    private List<ServiceDefinition> parseServices(String json) {
        ObjectMapper mapper = objectMapper != null ? objectMapper : defaultMapper();
        return ServiceDefinitionParser.parse(json, mapper);}

    private static ObjectMapper defaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper;
    }
}
