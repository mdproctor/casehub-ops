package io.casehub.ops.app.api;

import io.casehub.ops.app.rest.ApprovalResource;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@McpDomain(value = "ops/approvals", basePath = "/api/ops/approvals")
@ApplicationScoped
public class OpsApprovalApi {

    @Inject ApprovalResource resource;

    @PlatformQuery("List pending approvals")
    @RestPath("/")
    public Object listApprovals() {
        return resource.listApprovals(null).getEntity();
    }

    @PlatformQuery("Get approval details")
    @RestPath("/{id}")
    public Object getApproval(@PathParam UUID id) {
        return resource.getApproval(id, null).getEntity();
    }

    @PlatformMutation("Approve a pending item")
    @RestPath("/{id}/approve")
    public Object approve(@PathParam UUID id, String actorId) {
        return resource.approve(id, new ApprovalResource.ApproveRequest(actorId), null).getEntity();
    }

    @PlatformMutation("Reject a pending item")
    @RestPath("/{id}/reject")
    public Object reject(@PathParam UUID id, String actorId, String reason) {
        return resource.reject(id, new ApprovalResource.RejectRequest(actorId, reason), null).getEntity();
    }
}
