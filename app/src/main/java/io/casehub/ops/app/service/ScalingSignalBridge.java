package io.casehub.ops.app.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ScalingSignalBridge {

    private static final Logger LOG = Logger.getLogger(ScalingSignalBridge.class.getName());

    private final CaseSignaler signaler;

    @Inject
    public ScalingSignalBridge(io.casehub.api.engine.CaseHubRuntime runtime) {
        this((caseId, path, value) -> runtime.signal(caseId, path, value));
    }

    ScalingSignalBridge(CaseSignaler signaler) {
        this.signaler = signaler;
    }

    void onScalingRequested(@ObservesAsync ScalingRequestedEvent event) {
        var spec = new LinkedHashMap<String, Object>();
        spec.put("serviceId", event.serviceId());
        spec.put("targetReplicas", event.targetReplicas());
        spec.put("currentReplicas", event.currentReplicas());
        spec.put("applicationId", event.applicationId());
        spec.put("tenancyId", event.tenancyId());
        spec.put("reason", event.reason());
        spec.put("minReplicas", event.policy().minReplicas());
        spec.put("maxReplicas", event.policy().maxReplicas());
        if (event.policy().cooldownPeriod() != null) {
            spec.put("cooldownSeconds", event.policy().cooldownPeriod().toSeconds());
        }

        try {
            signaler.signal(event.appCaseId(), "scalingRequired", spec);
            LOG.fine(() -> "Signaled scaling for app " + event.applicationId()
                    + " service " + event.serviceId()
                    + " target=" + event.targetReplicas());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to signal scaling for case " + event.appCaseId(), e);
        }
    }
}
