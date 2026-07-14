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
public class NodeConvergenceTracker {

    private static final Logger LOG = Logger.getLogger(NodeConvergenceTracker.class.getName());

    @FunctionalInterface
    public interface ConvergenceSignaler {
        void signal(UUID caseId, String path, Object value);
    }

    record CaseTracking(Set<String> pendingNodeIds,
                        String signalPath, Object signalValue) {}

    private final ConcurrentHashMap<UUID, CaseTracking> tracked = new ConcurrentHashMap<>();
    private final ConvergenceSignaler signaler;
    private final ObjectMapper objectMapper;

    @Inject
    public NodeConvergenceTracker(io.casehub.api.engine.CaseHubRuntime runtime, ObjectMapper objectMapper) {
        this.signaler = (caseId, path, value) -> runtime.signal(caseId, path, value);
        this.objectMapper = objectMapper;
    }

    public NodeConvergenceTracker(ConvergenceSignaler signaler, ObjectMapper objectMapper) {
        this.signaler = signaler;
        this.objectMapper = objectMapper;
    }

    public void register(UUID caseId, Set<String> nodeIds,
                          String signalPath, Object signalValue) {
        var pending = ConcurrentHashMap.<String>newKeySet();
        pending.addAll(nodeIds);
        tracked.put(caseId, new CaseTracking(pending, signalPath, signalValue));
        LOG.fine(() -> "Tracking convergence for case " + caseId + " with " + nodeIds.size()
                       + " nodes, signal path: " + signalPath);
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

        for (var entry : tracked.entrySet()) {
            UUID caseId = entry.getKey();
            CaseTracking tracking = entry.getValue();
            if (tracking.pendingNodeIds().remove(recoveredNodeId) && tracking.pendingNodeIds().isEmpty()) {
                tracked.remove(caseId);
                try {
                    signaler.signal(caseId, tracking.signalPath(), tracking.signalValue());
                    LOG.info("Case " + caseId + " converged — all nodes recovered, signaled " + tracking.signalPath());
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to signal convergence for case " + caseId, e);
                }
            }
        }
    }

    public boolean isTracking(UUID caseId) {
        return tracked.containsKey(caseId);
    }
}
