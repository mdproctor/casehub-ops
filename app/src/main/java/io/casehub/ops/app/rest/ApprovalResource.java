package io.casehub.ops.app.rest;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.ops.api.approval.ApprovalPlan;
import io.casehub.ops.api.approval.PlanStore;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.*;
import io.casehub.ops.app.k8s.KubernetesEventSource;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.repository.WorkItemQuery;
import io.casehub.work.runtime.service.WorkItemService;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Blocking
@ApplicationScoped
@Path("/api/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApprovalResource {

    private final WorkItemService       workItemService;
    private final PlanStore             planStore;
    private final KubernetesEventSource eventSource;

    @Inject
    public ApprovalResource(WorkItemService workItemService,
                            PlanStore planStore,
                            KubernetesEventSource eventSource) {
        this.workItemService = workItemService;
        this.planStore       = planStore;
        this.eventSource     = eventSource;
    }

    @GET
    public Response listApprovals(@Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        var items = workItemService.scan(
                WorkItemQuery.builder()
                             .type("desiredstate-approval")
                             .tenancyId(tenancyId)
                             .build());
        var views = items.stream().map(this::toView).toList();
        return Response.ok(views).build();
    }

    @GET
    @Path("/{id}")
    public Response getApproval(@PathParam("id") UUID id,
                                @Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        var    item      = workItemService.findById(id).orElse(null);
        if (item == null) {return Response.status(Response.Status.NOT_FOUND).build();}
        if (!tenancyId.equals(item.tenancyId)) {return Response.status(Response.Status.FORBIDDEN).build();}
        return Response.ok(toView(item)).build();
    }

    @POST
    @Path("/{id}/approve")
    public Response approve(@PathParam("id") UUID id,
                            ApproveRequest request,
                            @Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        var    item      = workItemService.findById(id).orElse(null);
        if (item == null) {return Response.status(Response.Status.NOT_FOUND).build();}
        if (!tenancyId.equals(item.tenancyId)) {return Response.status(Response.Status.FORBIDDEN).build();}

        workItemService.completeFromSystem(id, request.actorId(), "approve");

        if (item.payload != null) {
            planStore.retrieve(item.payload).ifPresent(plan ->
                                                               eventSource.emitDrift(plan.nodeId()));
        }

        return Response.accepted().build();
    }

    @POST
    @Path("/{id}/reject")
    public Response reject(@PathParam("id") UUID id,
                           RejectRequest request,
                           @Context ContainerRequestContext ctx) {
        String tenancyId = (String) ctx.getProperty(TenancyFilter.TENANCY_PROPERTY);
        var    item      = workItemService.findById(id).orElse(null);
        if (item == null) {return Response.status(Response.Status.NOT_FOUND).build();}
        if (!tenancyId.equals(item.tenancyId)) {return Response.status(Response.Status.FORBIDDEN).build();}

        workItemService.rejectFromSystem(id, request.actorId(), request.reason());
        return Response.accepted().build();
    }

    private ApprovalView toView(WorkItem item) {
        var planOpt = item.payload != null
                      ? planStore.retrieve(item.payload) : Optional.<ApprovalPlan>empty();
        if (planOpt.isPresent()) {
            var    plan      = planOpt.get();
            String cluster   = null;
            String namespace = null;
            if (plan.originalSpec() instanceof InfraDesiredNodeSpec wrapper) {
                cluster   = wrapper.backendId();
                namespace = extractNamespace(wrapper.resourceSpec());
            }
            return new ApprovalView(
                    item.id, plan.nodeId().value(), plan.action().name(),
                    plan.risk(), plan.summary(), cluster, namespace,
                    item.status.name(), item.assigneeId, item.createdAt);
        }
        return new ApprovalView(
                item.id, null, null, null, item.title,
                null, null, item.status.name(), item.assigneeId, item.createdAt);
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

    public record ApproveRequest(String actorId) {}

    public record RejectRequest(String actorId, String reason) {}

    public record ApprovalView(UUID workItemId, String nodeId, String action,
                               RiskClassification risk, String summary, String cluster,
                               String namespace, String status, String assigneeId,
                               Instant createdAt) {}
}
