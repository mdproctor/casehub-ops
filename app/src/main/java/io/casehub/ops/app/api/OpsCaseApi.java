package io.casehub.ops.app.api;

import io.casehub.ops.app.entity.ApplicationEntity;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@McpDomain(value = "ops/cases", app = "ops", basePath = "/api/ops/cases")
@ApplicationScoped
public class OpsCaseApi {

    @Inject io.casehub.api.engine.CaseHubRuntime caseHubRuntime;

    @PlatformQuery("List cases for an application")
    @RestPath("/{applicationId}")
    public CaseEventLog listCases(@PathParam UUID applicationId) {
        var app = ApplicationEntity.<ApplicationEntity>findById(applicationId);
        if (app == null) { return null; }
        if (app.engineCaseId == null) { return new CaseEventLog(null, 0, List.of()); }
        var eventLog = caseHubRuntime.eventLog(app.engineCaseId);
        return new CaseEventLog(app.engineCaseId, eventLog.size(), eventLog);
    }

    @PlatformQuery("Get a specific case")
    @RestPath("/{applicationId}/{caseId}")
    public CaseEventLog getCase(@PathParam UUID applicationId, @PathParam UUID caseId) {
        try {
            var eventLog = caseHubRuntime.eventLog(caseId);
            return new CaseEventLog(caseId, eventLog.size(), eventLog);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record CaseEventLog(UUID caseId, int eventCount, List<?> events) {}
}
