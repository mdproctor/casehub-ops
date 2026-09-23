package io.casehub.ops.app.api;

import io.casehub.ops.api.approval.ApprovalAuthorizer;
import io.casehub.ops.api.approval.ApprovalPlan;
import io.casehub.ops.api.approval.PlanStore;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.InfraNodeSpec;
import io.casehub.ops.api.infra.K8sConfigMapSpec;
import io.casehub.ops.api.infra.K8sDeploymentSpec;
import io.casehub.ops.api.infra.K8sIngressSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.K8sServiceSpec;
import io.casehub.ops.app.k8s.KubernetesEventSource;
import io.casehub.ops.app.rest.ApprovalView;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemQuery;
import io.casehub.work.runtime.service.WorkItemService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@McpDomain(value = "ops/approvals", basePath = "/api/ops/approvals")
@ApplicationScoped
public class OpsApprovalApi {

    @Inject WorkItemService workItemService;
    @Inject PlanStore planStore;
    @Inject ApprovalAuthorizer authorizer;
    @Inject CurrentPrincipal principal;
    @Inject KubernetesEventSource eventSource;

    @PlatformQuery("List pending approvals")
    @RestPath("/")
    public List<ApprovalView> listApprovals() {
        var items = workItemService.scan(
                WorkItemQuery.builder()
                             .type("desiredstate-approval")
                             .build());
        return items.stream().map(this::toView).toList();
    }

    @PlatformQuery("Get approval details")
    @RestPath("/{id}")
    public ApprovalView getApproval(@PathParam UUID id) {
        return workItemService.findById(id).map(this::toView).orElse(null);
    }

    @PlatformMutation("Approve a pending item")
    @RestPath("/{id}/approve")
    public void approve(@PathParam UUID id, String actorId) {
        var item = workItemService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + id));
        RiskClassification risk = resolveRisk(item);
        var authResult = authorizer.authorize(risk, actorId, principal.roles());
        if (authResult instanceof ApprovalAuthorizer.AuthorizationResult.Denied denied) {
            throw new SecurityException(denied.reason());
        }
        workItemService.completeFromSystem(id, actorId, "approve");
        if (item.payload() != null) {
            planStore.retrieve(item.payload()).ifPresent(plan ->
                    eventSource.emitDrift(plan.nodeId()));
        }
    }

    @PlatformMutation("Reject a pending item")
    @RestPath("/{id}/reject")
    public void reject(@PathParam UUID id, String actorId, String reason) {
        var item = workItemService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Approval not found: " + id));
        RiskClassification risk = resolveRisk(item);
        var authResult = authorizer.authorize(risk, actorId, principal.roles());
        if (authResult instanceof ApprovalAuthorizer.AuthorizationResult.Denied denied) {
            throw new SecurityException(denied.reason());
        }
        workItemService.rejectFromSystem(id, actorId, reason);
    }

    private RiskClassification resolveRisk(WorkItem item) {
        if (item.payload() == null) { return RiskClassification.LOW; }
        return planStore.retrieve(item.payload())
                        .map(ApprovalPlan::risk)
                        .orElse(RiskClassification.LOW);
    }

    private ApprovalView toView(WorkItem item) {
        var planOpt = item.payload() != null
                      ? planStore.retrieve(item.payload()) : Optional.<ApprovalPlan>empty();
        if (planOpt.isPresent()) {
            var plan = planOpt.get();
            String cluster = null;
            String namespace = null;
            if (plan.originalSpec() instanceof InfraDesiredNodeSpec wrapper) {
                cluster = wrapper.backendId();
                namespace = extractNamespace(wrapper.resourceSpec());
            }
            return new ApprovalView(
                    item.id(), plan.nodeId().value(), plan.action().name(),
                    plan.risk(), plan.summary(), cluster, namespace,
                    item.status().name(), item.assigneeId(), item.createdAt());
        }
        return new ApprovalView(
                item.id(), null, null, null, item.title(),
                null, null, item.status().name(), item.assigneeId(), item.createdAt());
    }

    private String extractNamespace(InfraNodeSpec spec) {
        return switch (spec) {
            case K8sNamespaceSpec s -> s.name();
            case K8sDeploymentSpec s -> s.namespace();
            case K8sServiceSpec s -> s.namespace();
            case K8sIngressSpec s -> s.namespace();
            case K8sConfigMapSpec s -> s.namespace();
            default -> null;
        };
    }
}
