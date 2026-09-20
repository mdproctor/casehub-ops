package io.casehub.ops.app.api;

import io.casehub.ops.app.rest.ServiceOperationResource;
import io.casehub.ops.app.rest.dto.ScaleServiceRequest;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.UUID;

@McpDomain(value = "ops/services", basePath = "/api/ops/services")
@ApplicationScoped
public class OpsServiceApi {

    @Inject ServiceOperationResource resource;

    @PlatformQuery("Get service status and health dimensions")
    @RestPath("/{applicationId}/{serviceId}/status")
    public Object getServiceStatus(@PathParam UUID applicationId, @PathParam String serviceId) {
        return resource.getStatus(applicationId, serviceId).getEntity();
    }

    @PlatformMutation("Scale a service")
    @RestPath("/{applicationId}/{serviceId}/scale")
    public Object scaleService(@PathParam UUID applicationId, @PathParam String serviceId,
                                ScaleServiceRequest request) {
        return resource.scale(applicationId, serviceId, request).getEntity();
    }

    @PlatformMutation("Upgrade a service image")
    @RestPath("/{applicationId}/{serviceId}/upgrade")
    public Object upgradeService(@PathParam UUID applicationId, @PathParam String serviceId,
                                  Map<String, String> body) {
        return resource.upgrade(applicationId, serviceId, body).getEntity();
    }
}
