package io.casehub.ops.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.ReconciliationCompletedData;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects when decommissioned applications have fully converged on an empty
 * graph, then stops their reconciliation loops and cleans up.
 *
 * <p>Registration: {@link ApplicationLifecycleService#decommission} calls
 * {@link #registerDecommission} after updating desired graphs to empty.
 *
 * <p>Cancellation: {@link ApplicationLifecycleService#deploy} calls
 * {@link #cancelDecommission} before starting or updating loops. This
 * removes the tracking entry without stopping loops — the loops are still
 * running and deploy() will immediately updateDesired() them with the
 * real graph.
 *
 * <p>Convergence detection: the {@link ObservesAsync} CDI observer receives
 * CloudEvents from ReconciliationLoop. For tracked decommission keys,
 * convergence is detected when {@code removalsCount == 0 && faultCount == 0}.
 * On convergence: stop the loop, remove from the active loop index, and
 * transition to DECOMMISSIONED when all keys converge.
 *
 * <p>Timeout: a {@code @Scheduled(every = "1m")} method force-stops loops
 * after a configurable timeout (default 10 min).
 */
@ApplicationScoped
public class DecommissionCompletionHandler {

    private static final Logger LOG = Logger.getLogger(DecommissionCompletionHandler.class.getName());
    private static final long                                 TIMEOUT_MINUTES = 10;
    private final Consumer<String> loopStopper;
    private final Consumer<String> keyRemover;
    private final Consumer<UUID>   statusTransitioner;
    /**
     * appId -> Set of composite keys still pending convergence
     */
    private final        ConcurrentHashMap<UUID, Set<String>> tracking        = new ConcurrentHashMap<>();
    /**
     * appId -> registration time for staleness detection
     */
    private final ConcurrentHashMap<UUID, java.time.Instant> registeredAt = new ConcurrentHashMap<>();


    @Inject
    ObjectMapper objectMapper;

    /**
     * CDI constructor — delegates to ReconciliationLoop.stop(), ApplicationLifecycleService.removeLoopKey(), and markDecommissioned().
     */
    @Inject
    public DecommissionCompletionHandler(ReconciliationLoop reconciliationLoop,
                                         ApplicationLifecycleService lifecycleService) {
        this.loopStopper        = reconciliationLoop::stop;
        this.keyRemover         = lifecycleService::removeLoopKey;
        this.statusTransitioner = lifecycleService::markDecommissioned;
    }

    /**
     * Test constructor with functional delegates.
     */
    DecommissionCompletionHandler(Consumer<String> loopStopper, Consumer<String> keyRemover,
                                  Consumer<UUID> statusTransitioner) {
        this.loopStopper        = loopStopper;
        this.keyRemover         = keyRemover;
        this.statusTransitioner = statusTransitioner;
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
     * Registers an application for decommission tracking. The given composite
     * keys are the set of active loops that need to converge (reach empty state)
     * before the application can transition to DECOMMISSIONED.
     */
    public void registerDecommission(UUID appId, Set<String> compositeKeys) {
        tracking.put(appId, ConcurrentHashMap.newKeySet());
        tracking.get(appId).addAll(compositeKeys);
        registeredAt.put(appId, java.time.Instant.now());
        LOG.fine(() -> "Registered decommission for app " + appId + " with " + compositeKeys.size() + " keys");
    }

    /**
     * Cancels decommission tracking for an application. Called by deploy()
     * when a decommissioning application is being re-deployed. Does NOT stop
     * loops — the caller will update them with the new desired graph.
     */
    public void cancelDecommission(UUID appId) {
        Set<String> removed = tracking.remove(appId);
        registeredAt.remove(appId);
        if (removed != null) {
            LOG.fine(() -> "Cancelled decommission tracking for app " + appId);
        }
    }

    /**
     * CDI async observer for CloudEvents fired by ReconciliationLoop.
     * Filters for reconciliation.completed events and checks for decommission
     * convergence: removalsCount == 0 && faultCount == 0.
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
        if (!isTrackedKey(compositeKey)) {
            return;
        }

        boolean converged = data.removalsCount() == 0 && data.faultCount() == 0;
        if (converged) {
            onKeyConverged(compositeKey);
        }
    }

    /**
     * Handles convergence of a single composite key. Stops the reconciliation
     * loop, removes the key from the active loop index, and checks whether all
     * keys for the application have converged.
     */
    void onKeyConverged(String compositeKey) {
        loopStopper.accept(compositeKey);
        keyRemover.accept(compositeKey);
        LOG.fine(() -> "Decommission key converged: " + compositeKey);

        // Find and update the tracking entry for the parent app
        for (var entry : tracking.entrySet()) {
            UUID        appId = entry.getKey();
            Set<String> keys  = entry.getValue();
            if (keys.remove(compositeKey) && keys.isEmpty()) {
                tracking.remove(appId);
                statusTransitioner.accept(appId);
                LOG.info("App " + appId + " fully decommissioned — all loops converged and stopped");
            }
        }
    }

    /**
     * Returns true if the application is being tracked for decommission.
     */
    public boolean isTracking(UUID appId) {
        return tracking.containsKey(appId);
    }

    @io.quarkus.scheduler.Scheduled(every = "1m")
    void cleanupStaleDecommissions() {
        cleanupStaleDecommissions(java.time.Instant.now());
    }

    void cleanupStaleDecommissions(java.time.Instant now) {
        for (var entry : registeredAt.entrySet()) {
            UUID              appId      = entry.getKey();
            java.time.Instant registered = entry.getValue();
            if (java.time.Duration.between(registered, now).toMinutes() >= TIMEOUT_MINUTES) {
                Set<String> keys = tracking.remove(appId);
                registeredAt.remove(appId);
                if (keys != null) {
                    for (String key : keys) {
                        loopStopper.accept(key);
                        keyRemover.accept(key);
                    }
                    statusTransitioner.accept(appId);
                    LOG.warning("Decommission for app " + appId + " timed out after " + TIMEOUT_MINUTES + " minutes — force-stopping loops");
                }
            }
        }
    }

    /**
     * Returns true if the composite key is tracked by any decommission entry.
     */
    private boolean isTrackedKey(String compositeKey) {
        for (Set<String> keys : tracking.values()) {
            if (keys.contains(compositeKey)) {
                return true;
            }
        }
        return false;
    }
}
