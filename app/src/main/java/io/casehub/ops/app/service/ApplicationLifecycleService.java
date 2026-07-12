package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.entity.ClusterReferenceEntity;
import io.casehub.ops.app.entity.DeploymentRecordEntity;
import io.casehub.ops.app.goal.ApplicationGoalCompiler;
import io.casehub.ops.app.k8s.K8sClientRegistry;
import io.casehub.ops.app.model.ApplicationStatus;
import io.casehub.ops.app.model.DeploymentOutcome;
import io.casehub.ops.app.model.DeploymentTrigger;
import io.casehub.ops.app.model.ServiceDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ApplicationLifecycleService {

    @Inject
    ApplicationGoalCompiler goalCompiler;

    @Inject
    DesiredStateGraphFactory graphFactory;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ClusterService clusterService;

    @Inject
    ReconciliationLoop reconciliationLoop;

    @Inject
    K8sClientRegistry clientRegistry;

    @Inject
    DecommissionCompletionHandler decommissionHandler;

    @Inject
    DeploymentOutcomeTracker deploymentOutcomeTracker;

    /** Active loop index: clusterId -> Set of composite keys */
    private final ConcurrentHashMap<String, Set<String>> activeLoops = new ConcurrentHashMap<>();

    @Transactional
    public ApplicationEntity createDraft(String name, String description,
                                          String servicesJson, String tenancyId) {
        var app = new ApplicationEntity();
        app.name = name;
        app.description = description;
        app.servicesJson = servicesJson;
        app.tenancyId = tenancyId;
        app.status = ApplicationStatus.DRAFT;
        app.persist();
        return app;
    }

    public void deploy(UUID applicationId, String tenancyId) {
        var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
        if (app == null) throw new IllegalArgumentException("Application not found: " + applicationId);

        // Cancel any in-progress decommission before starting/updating loops
        decommissionHandler.cancelDecommission(app.id);

        List<ServiceDefinition> services = parseServices(app.servicesJson);
        List<ClusterReferenceEntity> clusters = clusterService.list(tenancyId);
        Set<String> clusterIds = new HashSet<>();

        for (ClusterReferenceEntity cluster : clusters) {
            // Ensure cluster is registered in K8sClientRegistry
            clientRegistry.register(cluster.id.toString(), cluster.apiUrl, cluster.credentialRef, cluster.trustCerts);

            var graph = goalCompiler.compileForCluster(services, cluster.id.toString(),
                    cluster.namespace, graphFactory);

            String key = tenancyId + ":" + applicationId + ":" + cluster.id;
            try {
                reconciliationLoop.start(key, graph);
            } catch (IllegalStateException e) {
                reconciliationLoop.updateDesired(key, graph);
            }
            trackLoopKey(cluster.id.toString(), key);
            clusterIds.add(cluster.id.toString());
        }

        updateStatus(app, ApplicationStatus.DEPLOYING);
        var deploymentRecord = recordDeployment(app, DeploymentTrigger.INITIAL, DeploymentOutcome.PENDING);

        // Register with deployment outcome tracker for cross-cluster convergence
        deploymentOutcomeTracker.registerDeployment(deploymentRecord.id, clusterIds);
        for (ClusterReferenceEntity cluster : clusters) {
            String key = tenancyId + ":" + applicationId + ":" + cluster.id;
            deploymentOutcomeTracker.associateKey(deploymentRecord.id, cluster.id.toString(), key);
        }
    }

    public void decommission(UUID applicationId, String tenancyId) {
        var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
        if (app == null) throw new IllegalArgumentException("Application not found: " + applicationId);

        List<ClusterReferenceEntity> clusters = clusterService.list(tenancyId);
        Set<String> compositeKeys = new HashSet<>();

        for (ClusterReferenceEntity cluster : clusters) {
            String key = tenancyId + ":" + applicationId + ":" + cluster.id;
            var emptyGraph = graphFactory.of(List.of(), List.of());
            reconciliationLoop.updateDesired(key, emptyGraph);
            compositeKeys.add(key);
        }

        decommissionHandler.registerDecommission(app.id, compositeKeys);
        updateStatus(app, ApplicationStatus.DECOMMISSIONING);
    }

    public ApplicationStatus deriveStatus(ApplicationEntity app) {
        if (app.engineCaseId == null) {
            return ApplicationStatus.DRAFT;
        }
        return app.status;
    }

    // --- Active loop index methods ---

    public void trackLoopKey(String clusterId, String compositeKey) {
        activeLoops.computeIfAbsent(clusterId, k -> ConcurrentHashMap.newKeySet())
                .add(compositeKey);
    }

    public void removeLoopKey(String compositeKey) {
        activeLoops.values().forEach(keys -> keys.remove(compositeKey));
    }

    public boolean hasActiveLoopsForCluster(String clusterId) {
        Set<String> keys = activeLoops.get(clusterId);
        return keys != null && !keys.isEmpty();
    }

    public Set<String> activeLoopKeysForApp(UUID appId) {
        String      appIdStr = appId.toString();
        Set<String> result   = ConcurrentHashMap.newKeySet();
        for (Set<String> keys : activeLoops.values()) {
            for (String key : keys) {
                // Composite key format: tenancyId:appId:clusterId
                // appId and clusterId are UUIDs (no colons) — parse from the right
                int lastColon       = key.lastIndexOf(':');
                int secondLastColon = key.lastIndexOf(':', lastColon - 1);
                if (secondLastColon >= 0) {
                    String extractedAppId = key.substring(secondLastColon + 1, lastColon);
                    if (extractedAppId.equals(appIdStr)) {
                        result.add(key);
                    }
                }
            }
        }
        return result;}

    // --- Internal methods ---

    @Transactional
    void updateStatus(ApplicationEntity app, ApplicationStatus status) {
        app.status = status;
    }

    /**
     * Transitions an application to DECOMMISSIONED status. Called by
     * {@link DecommissionCompletionHandler} when all reconciliation loops
     * have converged on the empty graph.
     */
    @Transactional
    public void markDecommissioned(UUID applicationId) {
        var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
        if (app != null) {
            app.status = ApplicationStatus.DECOMMISSIONED;
        }
    }

    @Transactional
    DeploymentRecordEntity recordDeployment(ApplicationEntity app, DeploymentTrigger trigger, DeploymentOutcome outcome) {
        var record = new DeploymentRecordEntity();
        record.applicationId = app.id;
        record.topologyJson = app.servicesJson;
        record.trigger = trigger;
        record.outcome = outcome;
        record.persist();
        return record;
    }

    private List<ServiceDefinition> parseServices(String json) {return ServiceDefinitionParser.parse(json, objectMapper);}
}
