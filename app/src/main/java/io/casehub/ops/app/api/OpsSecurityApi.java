package io.casehub.ops.app.api;

import io.casehub.ops.app.rest.SecurityResource;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@McpDomain(value = "ops/security", basePath = "/api/ops/security")
@ApplicationScoped
public class OpsSecurityApi {

    @Inject SecurityResource resource;

    @PlatformQuery("Get CVEs for an application")
    @RestPath("/{applicationId}/cves")
    public Object getCves(@PathParam UUID applicationId) {
        return resource.getCves(applicationId).getEntity();
    }

    @PlatformMutation("Trigger CVE scan for an application")
    @RestPath("/{applicationId}/cves/scan")
    public Object scanCves(@PathParam UUID applicationId) {
        return resource.scanCves(applicationId, null).getEntity();
    }

    @PlatformQuery("Get security posture for an application")
    @RestPath("/{applicationId}/posture")
    public Object getPosture(@PathParam UUID applicationId) {
        return resource.getPosture(applicationId).getEntity();
    }
}
