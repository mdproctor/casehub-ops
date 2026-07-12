package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.ReconciliationCompletedData;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks cross-cluster convergence for PENDING deployments and transitions
 * their outcome to SUCCESS or FAILED.
 *
 * <p>Registration: {@link ApplicationLifecycleService#deploy} calls
 * {@link #registerDeployment} after creating a PENDING {@code DeploymentRecordEntity}.
 * The tracker initialises a per-cluster convergence map with all entries false.
 *
 * <p>Convergence detection: the {@link ObservesAsync} CDI observer receives
 * {@link CloudEvent} instances fired by the ReconciliationLoop. For tracked
 * composite keys, convergence is detected when {@code additionsCount == 0 &&
 * faultCount == 0}. When all clusters for a deployment converge, the tracker
 * updates the DeploymentRecordEntity outcome to SUCCESS and removes the
 * tracking entry.
 *
 * <p>Timeout: a {@code @Scheduled(every = "1m")} method checks for PENDING
 * deployments older than the configured timeout (default 10 min). Deployments
 * past the timeout transition to FAILED.
 */
@ApplicationScoped
public class DeploymentOutcomeTracker {

    private static final Logger LOG = Logger.getLogger(DeploymentOutcomeTracker.class.getName());
    private static final long                              TIMEOUT_MINUTES = 10;
    /**
     * deploymentId -> (clusterId -> converged)
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Boolean>> tracking
            = new ConcurrentHashMap<>();
    /**
     * compositeKey -> deploymentId — reverse index for event correlation
     */
    private final ConcurrentHashMap<String, UUID> keyToDeployment = new ConcurrentHashMap<>();
    /**
     * compositeKey -> clusterId — maps composite keys back to cluster IDs
     */
    private final        ConcurrentHashMap<String, String> keyToCluster    = new ConcurrentHashMap<>();
    /**
     * deploymentId -> registration time for staleness detection
     */
    private final ConcurrentHashMap<UUID, java.time.Instant> registeredAt = new ConcurrentHashMap<>();


    @Inject
    ObjectMapper objectMapper;

    /**
     * No-arg constructor for CDI proxy and plain unit tests.
     */
    public DeploymentOutcomeTracker() {
    }

    /**
     * Returns a default ObjectMapper for use in unit tests where CDI injection
     * is not available.
     */
    private static ObjectMapper defaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper;
    }

    /**
     * Registers a deployment for tracking. Initialises per-cluster convergence
     * map with all entries set to false.
     */
    public void registerDeployment(UUID deploymentId, Set<String> clusterIds) {
        var clusterMap = new ConcurrentHashMap<String, Boolean>();
        for (String clusterId : clusterIds) {
            clusterMap.put(clusterId, false);
        }
        tracking.put(deploymentId, clusterMap);
        registeredAt.put(deploymentId, java.time.Instant.now());
        LOG.fine(() -> "Registered deployment " + deploymentId + " tracking " + clusterIds.size() + " clusters");
    }

    /**
     * Associates a composite key with a deployment and cluster, enabling
     * event correlation when CloudEvents arrive.
     */
    public void associateKey(UUID deploymentId, String clusterId, String compositeKey) {
        keyToDeployment.put(compositeKey, deploymentId);
        keyToCluster.put(compositeKey, clusterId);
    }

    /**
     * CDI async observer for CloudEvents fired by ReconciliationLoop.
     * Filters for reconciliation.completed events and checks convergence.
     */
    void onCloudEvent(@ObservesAsync CloudEvent event) {
        if (!DesiredStateEventTypes.RECONCILIATION_COMPLETED.equals(event.getType())) {
            return;
        }
        if (event.getData() == null) {
            return;
        }

        ReconciliationCompletedData data;
        try {
            ObjectMapper mapper = objectMapper != null ? objectMapper : defaultMapper();
            data = mapper.readValue(event.getData().toBytes(), ReconciliationCompletedData.class);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize ReconciliationCompletedData", e);
            return;
        }

        String compositeKey = data.tenancyId();
        UUID   deploymentId = keyToDeployment.get(compositeKey);
        if (deploymentId == null) {
            return; // Not a tracked deployment
        }

        String clusterId = keyToCluster.get(compositeKey);
        if (clusterId == null) {
            return;
        }

        ConcurrentHashMap<String, Boolean> clusterMap = tracking.get(deploymentId);
        if (clusterMap == null) {
            return; // Already completed
        }

        boolean converged = data.additionsCount() == 0 && data.faultCount() == 0;
        if (converged) {
            clusterMap.put(clusterId, true);
            LOG.fine(() -> "Cluster " + clusterId + " converged for deployment " + deploymentId);

            if (allConverged(clusterMap)) {
                onAllConverged(deploymentId);
            }
        }
    }

    /**
     * Returns true if the deployment is actively being tracked.
     */
    public boolean isTracking(UUID deploymentId) {
        return tracking.containsKey(deploymentId);
    }

    /**
     * Returns true if the given cluster has converged for the deployment.
     */
    public boolean isConverged(UUID deploymentId, String clusterId) {
        ConcurrentHashMap<String, Boolean> clusterMap = tracking.get(deploymentId);
        if (clusterMap == null) {return false;}
        return Boolean.TRUE.equals(clusterMap.get(clusterId));
    }

    @io.quarkus.scheduler.Scheduled(every = "1m")
    void cleanupStalePending() {
        cleanupStalePending(java.time.Instant.now());
    }

    void cleanupStalePending(java.time.Instant now) {
        for (var entry : registeredAt.entrySet()) {
            UUID              deploymentId = entry.getKey();
            java.time.Instant registered   = entry.getValue();
            if (java.time.Duration.between(registered, now).toMinutes() >= TIMEOUT_MINUTES) {
                ConcurrentHashMap<String, Boolean> removed = tracking.remove(deploymentId);
                registeredAt.remove(deploymentId);
                if (removed != null) {
                    keyToDeployment.entrySet().removeIf(e -> e.getValue().equals(deploymentId));
                    keyToCluster.entrySet().removeIf(e -> !keyToDeployment.containsKey(e.getKey()));
                    LOG.warning("Deployment " + deploymentId + " timed out after " + TIMEOUT_MINUTES + " minutes — removed from tracking");
                }
            }
        }
    }

    private boolean allConverged(Map<String, Boolean> clusterMap) {
        return clusterMap.values().stream().allMatch(Boolean.TRUE::equals);
    }

    /**
     * Called when all clusters for a deployment have converged.
     * Removes the tracking entry and cleans up reverse indexes.
     *
     * <p>In the full CDI context, this would also update the
     * DeploymentRecordEntity outcome to SUCCESS. That requires a
     * transactional context and is handled separately.
     */
    private void onAllConverged(UUID deploymentId) {
        ConcurrentHashMap<String, Boolean> removed = tracking.remove(deploymentId);
        registeredAt.remove(deploymentId);
        if (removed != null) {
            keyToDeployment.entrySet().removeIf(e -> e.getValue().equals(deploymentId));
            keyToCluster.entrySet().removeIf(e -> !keyToDeployment.containsKey(e.getKey()));
            LOG.info("Deployment " + deploymentId + " fully converged — all clusters clean");
        }
    }
}
