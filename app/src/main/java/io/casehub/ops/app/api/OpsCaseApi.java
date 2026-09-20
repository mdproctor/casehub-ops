package io.casehub.ops.app.api;

import io.casehub.ops.app.rest.CaseResource;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@McpDomain(value = "ops/cases", basePath = "/api/ops/cases")
@ApplicationScoped
public class OpsCaseApi {

    @Inject CaseResource resource;

    @PlatformQuery("List cases for an application")
    @RestPath("/{applicationId}")
    public Object listCases(@PathParam UUID applicationId) {
        return resource.listCases(applicationId).getEntity();
    }

    @PlatformQuery("Get a specific case")
    @RestPath("/{applicationId}/{caseId}")
    public Object getCase(@PathParam UUID applicationId, @PathParam UUID caseId) {
        return resource.getCase(applicationId, caseId).getEntity();
    }
}
