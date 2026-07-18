package io.casehub.ops.app.rest;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.ops.api.approval.ApprovalPlan;
import io.casehub.ops.api.approval.InMemoryPlanStore;
import io.casehub.ops.api.approval.PlanStore;
import io.casehub.ops.api.approval.RiskClassification;
import io.casehub.ops.api.infra.InfraDesiredNodeSpec;
import io.casehub.ops.api.infra.K8sNamespaceSpec;
import io.casehub.ops.api.infra.types.Labels;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.spi.WorkItemCreator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ApprovalResourceTest {

    @Inject
    WorkItemCreator workItemCreator;

    @Inject
    PlanStore planStore;

    @Test
    void listApprovals_returnsEnrichedView() {
        var spec = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("prod-billing", Labels.empty()),
                "kubernetes:ops-prod");
        var plan = new ApprovalPlan(
                NodeId.of("ns-1"), StepAction.DEPROVISION, RiskClassification.CRITICAL,
                "Deprovision namespace 'prod-billing' on kubernetes:ops-prod",
                "approval-test-tenant", spec, null);
        String planRef = planStore.store(plan);

        var workItem = workItemCreator.create(WorkItemCreateRequest.builder()
                .title("Approve deprovision: ns-1")
                .callerRef("desiredstate-approval:approval-test-tenant:ns-1:DEPROVISION")
                .payload(planRef)
                .tenancyId("approval-test-tenant")
                .types(java.util.List.of("desiredstate-approval"))
                .build());

        given()
                .header("X-Tenancy-ID", "approval-test-tenant")
                .when().get("/api/approvals")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].nodeId", equalTo("ns-1"))
                .body("[0].action", equalTo("DEPROVISION"))
                .body("[0].risk", equalTo("CRITICAL"))
                .body("[0].cluster", equalTo("kubernetes:ops-prod"))
                .body("[0].namespace", equalTo("prod-billing"));
    }

    @Test
    void approve_correctTenancy_returns202() {
        var spec = new InfraDesiredNodeSpec(
                new K8sNamespaceSpec("prod", Labels.empty()),
                "kubernetes:ops-prod");
        var plan = new ApprovalPlan(
                NodeId.of("ns-approve"), StepAction.DEPROVISION, RiskClassification.CRITICAL,
                "Deprovision namespace 'prod'",
                "approve-tenant", spec, null);
        String planRef = planStore.store(plan);

        var workItem = workItemCreator.create(WorkItemCreateRequest.builder()
                .title("Approve deprovision: ns-approve")
                .callerRef("desiredstate-approval:approve-tenant:ns-approve:DEPROVISION")
                .payload(planRef)
                .tenancyId("approve-tenant")
                .types(java.util.List.of("desiredstate-approval"))
                .build());

        given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "approve-tenant")
                .body("""
                    {"actorId": "admin"}
                    """)
                .when().post("/api/approvals/" + workItem.id() + "/approve")
                .then().statusCode(202);
    }

    @Test
    void approve_wrongTenancy_returns403() {
        var workItem = workItemCreator.create(WorkItemCreateRequest.builder()
                .title("Test approval")
                .callerRef("desiredstate-approval:tenant-a:node-1:PROVISION")
                .tenancyId("tenant-a")
                .types(java.util.List.of("desiredstate-approval"))
                .build());

        given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "tenant-b")
                .body("""
                    {"actorId": "admin"}
                    """)
                .when().post("/api/approvals/" + workItem.id() + "/approve")
                .then().statusCode(403);
    }

    @Test
    void approve_notFound_returns404() {
        given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "any-tenant")
                .body("""
                    {"actorId": "admin"}
                    """)
                .when().post("/api/approvals/00000000-0000-0000-0000-000000000099/approve")
                .then().statusCode(404);
    }

    @Test
    void reject_correctTenancy_returns202() {
        var workItem = workItemCreator.create(WorkItemCreateRequest.builder()
                .title("Test rejection")
                .callerRef("desiredstate-approval:reject-tenant:node-rej:PROVISION")
                .tenancyId("reject-tenant")
                .types(java.util.List.of("desiredstate-approval"))
                .build());

        given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "reject-tenant")
                .body("""
                    {"actorId": "admin", "reason": "too risky"}
                    """)
                .when().post("/api/approvals/" + workItem.id() + "/reject")
                .then().statusCode(202);
    }

    @Test
    void reject_wrongTenancy_returns403() {
        var workItem = workItemCreator.create(WorkItemCreateRequest.builder()
                .title("Test rejection cross-tenant")
                .callerRef("desiredstate-approval:tenant-x:node-rx:PROVISION")
                .tenancyId("tenant-x")
                .types(java.util.List.of("desiredstate-approval"))
                .build());

        given()
                .contentType("application/json")
                .header("X-Tenancy-ID", "tenant-y")
                .body("""
                    {"actorId": "admin", "reason": "should fail"}
                    """)
                .when().post("/api/approvals/" + workItem.id() + "/reject")
                .then().statusCode(403);
    }

    @Test
    void listApprovals_degradedView_whenPlanMissing() {
        var workItem = workItemCreator.create(WorkItemCreateRequest.builder()
                .title("Approve deprovision: ns-degraded")
                .callerRef("desiredstate-approval:degraded-tenant:ns-degraded:DEPROVISION")
                .payload("nonexistent-plan-ref")
                .tenancyId("degraded-tenant")
                .types(java.util.List.of("desiredstate-approval"))
                .build());

        given()
                .header("X-Tenancy-ID", "degraded-tenant")
                .when().get("/api/approvals")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].nodeId", nullValue())
                .body("[0].summary", equalTo("Approve deprovision: ns-degraded"));
    }
}
