package io.casehub.ops.app.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.NodeRecoveredData;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class DriftConvergenceHandler {

    private static final Logger LOG = Logger.getLogger(DriftConvergenceHandler.class.getName());

    @FunctionalInterface
    public interface ConvergenceSignaler {
        void signal(UUID caseId, String path, Object value);
    }

    private final ConcurrentHashMap<UUID, Set<String>> pendingNodes = new ConcurrentHashMap<>();
    private final ConvergenceSignaler signaler;
    private final ObjectMapper objectMapper;

    @Inject
    public DriftConvergenceHandler(io.casehub.api.engine.CaseHubRuntime runtime, ObjectMapper objectMapper) {
        this.signaler = (caseId, path, value) -> runtime.signal(caseId, path, value);
        this.objectMapper = objectMapper;
    }

    DriftConvergenceHandler(ConvergenceSignaler signaler, ObjectMapper objectMapper) {
        this.signaler = signaler;
        this.objectMapper = objectMapper;
    }

    public void registerDriftCase(UUID childCaseId, Set<String> driftedNodeIds) {
        var pending = ConcurrentHashMap.<String>newKeySet();
        pending.addAll(driftedNodeIds);
        pendingNodes.put(childCaseId, pending);
        LOG.fine(() -> "Tracking drift convergence for case " + childCaseId + " with " + driftedNodeIds.size() + " nodes");
    }

    void onCloudEvent(@ObservesAsync CloudEvent event) {
        if (!DesiredStateEventTypes.NODE_RECOVERED.equals(event.getType())) return;
        if (event.getData() == null) return;

        NodeRecoveredData data;
        try {
            data = objectMapper.readValue(event.getData().toBytes(), NodeRecoveredData.class);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize NodeRecoveredData", e);
            return;
        }

        String recoveredNodeId = data.nodeId();

        for (var entry : pendingNodes.entrySet()) {
            UUID caseId = entry.getKey();
            Set<String> pending = entry.getValue();
            if (pending.remove(recoveredNodeId) && pending.isEmpty()) {
                pendingNodes.remove(caseId);
                try {
                    signaler.signal(caseId, "remediationStatus", Map.of("remediationStatus", "converged"));
                    LOG.info("Drift case " + caseId + " converged — all nodes recovered");
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to signal convergence for case " + caseId, e);
                }
            }
        }
    }

    public boolean isTracking(UUID caseId) {
        return pendingNodes.containsKey(caseId);
    }
}
