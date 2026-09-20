package io.casehub.ops.app.api;

import io.casehub.ops.app.rest.ReconciliationResource;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@McpDomain(value = "ops/reconciliation", basePath = "/api/ops/reconciliation")
@ApplicationScoped
public class OpsReconciliationApi {

    @Inject ReconciliationResource resource;

    @PlatformQuery("Get reconciliation status for an application")
    @RestPath("/{applicationId}/status")
    public Object getStatus(@PathParam UUID applicationId) {
        return resource.getStatus(applicationId, null).getEntity();
    }

    @PlatformMutation("Trigger reconciliation for an application")
    @RestPath("/{applicationId}/trigger")
    public Object triggerReconciliation(@PathParam UUID applicationId) {
        return resource.trigger(applicationId).getEntity();
    }
}
