package io.casehub.ops.app.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.NodeDriftedData;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class DriftSignalBridge {

    private static final Logger LOG = Logger.getLogger(DriftSignalBridge.class.getName());

    private record AppRegistration(UUID appCaseId, String applicationId, String clusterId) {}

    record DriftTracker(Set<String> lastDriftedNodeIds, int consecutiveCount) {}

    private final ConcurrentHashMap<String, AppRegistration> registrations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DriftTracker> driftTrackers = new ConcurrentHashMap<>();
    private final CaseSignaler signaler;
    private final ObjectMapper objectMapper;

    @Inject
    public DriftSignalBridge(io.casehub.api.engine.CaseHubRuntime runtime, ObjectMapper objectMapper) {
        this.signaler = (caseId, path, value) -> runtime.signal(caseId, path, value);
        this.objectMapper = objectMapper;
    }

    DriftSignalBridge(CaseSignaler signaler, ObjectMapper objectMapper) {
        this.signaler = signaler;
        this.objectMapper = objectMapper;
    }

    public void registerApplication(String compositeKey, UUID appCaseId, String applicationId, String clusterId) {
        registrations.put(compositeKey, new AppRegistration(appCaseId, applicationId, clusterId));
    }

    public void deregisterApplication(String compositeKey) {
        registrations.remove(compositeKey);
    }

    public void resetDriftCount(String applicationId) {
        driftTrackers.remove(applicationId);
    }

    void onCloudEvent(@ObservesAsync CloudEvent event) {
        if (!DesiredStateEventTypes.NODE_DRIFTED.equals(event.getType())) return;
        if (event.getData() == null) return;

        NodeDriftedData data;
        try {
            data = objectMapper.readValue(event.getData().toBytes(), NodeDriftedData.class);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize NodeDriftedData", e);
            return;
        }

        AppRegistration reg = registrations.get(data.tenancyId());
        if (reg == null) return;

        int consecutiveCount = updateDriftTracker(reg.applicationId, data.nodeId());

        Map<String, Object> driftReport = Map.of(
                "driftDetails", List.of(Map.of("nodeId", data.nodeId(), "fields", List.of())),
                "clusterId", reg.clusterId,
                "applicationId", reg.applicationId,
                "detectedAt", Instant.now().toString(),
                "consecutiveDriftCount", consecutiveCount);

        try {
            signaler.signal(reg.appCaseId, "driftDetected", driftReport);
            LOG.fine(() -> "Signaled drift for app " + reg.applicationId + " node " + data.nodeId()
                    + " (consecutive: " + consecutiveCount + ")");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to signal drift for case " + reg.appCaseId, e);
        }
    }

    private int updateDriftTracker(String applicationId, String nodeId) {
        Set<String> currentNodes = Set.of(nodeId);
        DriftTracker existing = driftTrackers.get(applicationId);

        int newCount;
        if (existing != null && hasOverlap(existing.lastDriftedNodeIds, currentNodes)) {
            newCount = existing.consecutiveCount + 1;
        } else {
            newCount = 1;
        }

        driftTrackers.put(applicationId, new DriftTracker(currentNodes, newCount));
        return newCount;
    }

    private boolean hasOverlap(Set<String> a, Set<String> b) {
        for (String s : b) {
            if (a.contains(s)) return true;
        }
        return false;
    }

    private static ObjectMapper defaultMapper() {
        var mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper;
    }
}
