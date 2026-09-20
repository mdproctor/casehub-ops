package io.casehub.ops.app.api;

import io.casehub.ops.app.rest.ApplicationResource;
import io.casehub.ops.app.rest.dto.CreateApplicationRequest;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@McpDomain(value = "ops/applications", basePath = "/api/ops/applications")
@ApplicationScoped
public class OpsApplicationApi {

    @Inject ApplicationResource resource;

    @PlatformMutation("Create an application")
    @RestPath("/")
    public Object createApplication(CreateApplicationRequest request) {
        return resource.create(request, null).getEntity();
    }

    @PlatformQuery("List all applications")
    @RestPath("/")
    public Object listApplications() {
        return resource.list(null).getEntity();
    }

    @PlatformQuery("Get an application by ID")
    @RestPath("/{id}")
    public Object getApplication(@PathParam UUID id) {
        return resource.get(id).getEntity();
    }

    @PlatformMutation("Update an application")
    @RestPath("/{id}")
    public Object updateApplication(@PathParam UUID id, CreateApplicationRequest request) {
        return resource.update(id, request).getEntity();
    }

    @PlatformMutation("Delete an application")
    @RestPath("/{id}/delete")
    public Object deleteApplication(@PathParam UUID id) {
        return resource.delete(id, null).getEntity();
    }
}
