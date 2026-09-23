package io.casehub.ops.app.api;

import io.casehub.ops.app.model.CveRecord;
import io.casehub.ops.app.model.CveStatus;
import io.casehub.ops.app.persistence.CveStore;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@McpDomain(value = "ops/security", basePath = "/api/ops/security")
@ApplicationScoped
public class OpsSecurityApi {

    @Inject CveStore cveStore;

    @PlatformQuery("Get CVEs for an application")
    @RestPath("/{applicationId}/cves")
    public List<CveRecord> getCves(@PathParam UUID applicationId) {
        return cveStore.findByApplicationId(applicationId);
    }

    @PlatformMutation("Trigger CVE scan for an application")
    @RestPath("/{applicationId}/cves/scan")
    public void scanCves(@PathParam UUID applicationId) {
        // CVE scanning is triggered externally; this endpoint acknowledges the request
    }

    @PlatformQuery("Get security posture for an application")
    @RestPath("/{applicationId}/posture")
    public SecurityPosture getPosture(@PathParam UUID applicationId) {
        var cves = cveStore.findByApplicationId(applicationId);
        long detected = cves.stream().filter(c -> c.status() == CveStatus.DETECTED).count();
        long remediating = cves.stream().filter(c -> c.status() == CveStatus.REMEDIATING).count();
        long resolved = cves.stream().filter(c -> c.status() == CveStatus.RESOLVED).count();
        long escalated = cves.stream().filter(c -> c.status() == CveStatus.ESCALATED).count();
        return new SecurityPosture(cves.size(), detected, remediating, resolved, escalated);
    }

    public record SecurityPosture(int totalCves, long detected, long remediating, long resolved, long escalated) {}
}
