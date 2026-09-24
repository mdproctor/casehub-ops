package io.casehub.ops.app.api;

import io.casehub.ops.api.lifecycle.DimensionType;
import io.casehub.ops.api.lifecycle.ManagedServiceCategory;
import io.casehub.ops.api.lifecycle.OperationalDimension;
import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.ops.app.lifecycle.ServiceCaseRegistry;
import io.casehub.ops.app.rest.dto.ScaleServiceRequest;
import io.casehub.ops.app.service.ScalingService;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.UUID;

@McpDomain(value = "ops/services", app = "ops", basePath = "/api/ops/services", summary = "Service catalog and dependency management")
@ApplicationScoped
public class OpsServiceApi {

    @Inject ServiceCaseRegistry serviceCaseRegistry;
    @Inject ScalingService scalingService;
    @Inject io.casehub.api.engine.CaseHubRuntime caseHubRuntime;

    @PlatformQuery("Get service status and health dimensions")
    @RestPath("/{applicationId}/{serviceId}/status")
    public ServiceStatus getServiceStatus(@PathParam UUID applicationId, @PathParam String serviceId) {
        var ctx = serviceCaseRegistry.getByServiceId(serviceId);
        if (ctx == null) { return null; }
        return new ServiceStatus(serviceId, ctx.serviceName(), ctx.category(), ctx.dimensions());
    }

    @PlatformMutation("Scale a service")
    @RestPath("/{applicationId}/{serviceId}/scale")
    public void scaleService(@PathParam UUID applicationId, @PathParam String serviceId,
                              ScaleServiceRequest request) {
        var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
        if (app == null) { throw new IllegalArgumentException("Application not found: " + applicationId); }
        var response = scalingService.scale(app.id.toString(), app.engineCaseId, app.status,
                app.servicesJson, serviceId, request);
        if (response.getStatus() >= 400) {
            throw new IllegalStateException("Scale operation rejected (HTTP " + response.getStatus() + ")");
        }
    }

    @PlatformMutation("Upgrade a service image")
    @RestPath("/{applicationId}/{serviceId}/upgrade")
    public void upgradeService(@PathParam UUID applicationId, @PathParam String serviceId,
                                Map<String, String> body) {
        var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
        if (app == null) { throw new IllegalArgumentException("Application not found: " + applicationId); }
        if (app.engineCaseId == null) {
            throw new IllegalStateException("No active case for application");
        }
        String newImage = body != null ? body.get("newImage") : null;
        if (newImage == null || newImage.isBlank()) {
            throw new IllegalArgumentException("newImage is required");
        }
        caseHubRuntime.signal(app.engineCaseId, "upgradeRequested", Map.of(
                "serviceId", serviceId,
                "newImage", newImage,
                "applicationId", applicationId.toString(),
                "tenancyId", app.tenancyId));
    }

    public record ServiceStatus(String serviceId, String serviceName, ManagedServiceCategory category,
                                Map<DimensionType, OperationalDimension> dimensions) {}
}
