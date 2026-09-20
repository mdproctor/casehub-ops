package io.casehub.ops.app.api;

import io.casehub.ops.app.rest.DeploymentResource;
import io.casehub.ops.app.rest.dto.DeployRequest;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@McpDomain(value = "ops/deployments", basePath = "/api/ops/deployments")
@ApplicationScoped
public class OpsDeploymentApi {

    @Inject DeploymentResource resource;

    @PlatformMutation("Deploy an application")
    @RestPath("/{applicationId}/deploy")
    public Object deploy(@PathParam UUID applicationId, DeployRequest request) {
        return resource.deploy(applicationId, request, null).getEntity();
    }

    @PlatformQuery("List deployments for an application")
    @RestPath("/{applicationId}")
    public Object listDeployments(@PathParam UUID applicationId) {
        return resource.listDeployments(applicationId).getEntity();
    }

    @PlatformQuery("Get current deployment")
    @RestPath("/{applicationId}/current")
    public Object getCurrentDeployment(@PathParam UUID applicationId) {
        return resource.getCurrentDeployment(applicationId).getEntity();
    }

    @PlatformMutation("Rollback an application to previous deployment")
    @RestPath("/{applicationId}/rollback")
    public Object rollback(@PathParam UUID applicationId) {
        return resource.rollback(applicationId, null).getEntity();
    }
}
