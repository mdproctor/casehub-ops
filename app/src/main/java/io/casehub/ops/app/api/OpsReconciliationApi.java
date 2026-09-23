package io.casehub.ops.app.api;

import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.service.ApplicationEventBroadcaster;
import io.casehub.ops.app.service.ClusterService;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.PlatformStream;
import io.casehub.platform.api.mcp.RestPath;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@McpDomain(value = "ops/reconciliation", app = "ops", basePath = "/api/ops/reconciliation")
@ApplicationScoped
public class OpsReconciliationApi {

    @Inject io.casehub.desiredstate.runtime.ReconciliationLoop reconciliationLoop;
    @Inject ClusterService clusterService;
    @Inject EntityManager em;
    @Inject
            ApplicationEventBroadcaster broadcaster;


    @PlatformQuery("Get reconciliation status for an application")
    @RestPath("/{applicationId}/status")
    public ReconciliationStatus getStatus(@PathParam UUID applicationId) {
        var app = em.find(ApplicationEntity.class, applicationId);
        if (app == null) { return null; }

        var clusters = clusterService.list(app.tenancyId);
        var statuses = new ArrayList<ClusterReconciliationStatus>();
        for (var cluster : clusters) {
            String key = app.tenancyId + ":" + applicationId + ":" + cluster.id;
            var desired = reconciliationLoop.getDesired(key);
            statuses.add(new ClusterReconciliationStatus(
                    cluster.id.toString(), cluster.name,
                    desired != null, desired != null ? desired.nodes().size() : 0));
        }
        return new ReconciliationStatus(statuses);
    }

    @PlatformMutation("Trigger reconciliation for an application")
    @RestPath("/{applicationId}/trigger")
    public TriggerResult triggerReconciliation(@PathParam UUID applicationId) {
        return new TriggerResult("triggered");
    }

    public record ReconciliationStatus(List<ClusterReconciliationStatus> clusters) {}
    public record ClusterReconciliationStatus(String clusterId, String clusterName, boolean active, int nodeCount) {}
    public record TriggerResult(String status) {}

    @PlatformStream("Watch reconciliation events for an application")
    @RestPath("/{applicationId}/events")
    public Multi<ApplicationEventBroadcaster.BroadcastEvent> watchEvents(@PathParam UUID applicationId) {
        return Multi.createFrom().<ApplicationEventBroadcaster.BroadcastEvent>emitter(emitter -> {
            var handle = broadcaster.subscribe(applicationId,
                                               ApplicationEventBroadcaster.EventFilter.RECONCILIATION, emitter::emit);
            emitter.onTermination(() -> broadcaster.unsubscribe(handle));
        });
    }
}
