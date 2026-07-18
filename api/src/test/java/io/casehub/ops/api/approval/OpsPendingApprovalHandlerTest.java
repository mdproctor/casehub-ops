package io.casehub.ops.api.approval;

import io.casehub.desiredstate.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpsPendingApprovalHandlerTest {

    private OpsPendingApprovalHandler handler;
    private InMemoryPlanStore planStore;

    private static final NodeId NODE_1 = NodeId.of("node-1");
    private static final String TENANT = "tenant-1";

    @BeforeEach
    void setUp() {
        planStore = new InMemoryPlanStore();
        handler = new OpsPendingApprovalHandler(planStore);
    }

    private DesiredNode testNode() {
        return new DesiredNode(NODE_1, NodeType.of("agent"), new StubSpec(), io.casehub.desiredstate.api.HumanGating.NONE);
    }

    // --- check() returns None when nothing recorded ---

    @Test
    void checkReturnsNoneWhenEmpty() {
        var result = handler.check(testNode(), StepAction.PROVISION, TENANT);
        assertThat(result).isInstanceOf(ApprovalCheckResult.None.class);
    }

    // --- recordPending → check returns Pending ---

    @Test
    void recordPendingThenCheckReturnsPending() {
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "plan-ref-1");
        var result = handler.check(testNode(), StepAction.PROVISION, TENANT);
        assertThat(result).isInstanceOf(ApprovalCheckResult.Pending.class);
        assertThat(((ApprovalCheckResult.Pending) result).planReference()).isEqualTo("plan-ref-1");
    }

    @Test
    void recordPendingReturnsSkipped() {
        var outcome = handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "plan-ref-1");
        assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) outcome).reason()).contains("plan-ref-1");
    }

    // --- approve → check returns Approved ---

    @Test
    void approveTransitionsPendingToApproved() {
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "plan-ref-1");
        boolean result = handler.approve(NODE_1, StepAction.PROVISION, TENANT, "admin");
        assertThat(result).isTrue();

        var check = handler.check(testNode(), StepAction.PROVISION, TENANT);
        assertThat(check).isInstanceOf(ApprovalCheckResult.Approved.class);
        var approved = (ApprovalCheckResult.Approved) check;
        assertThat(approved.approval().planReference()).isEqualTo("plan-ref-1");
        assertThat(approved.approval().approvedBy()).isEqualTo("admin");
        assertThat(approved.approval().approvedAt()).isNotNull();
    }

    @Test
    void approvedNotConsumedOnRead() {
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "plan-ref-1");
        handler.approve(NODE_1, StepAction.PROVISION, TENANT, "admin");

        var check1 = handler.check(testNode(), StepAction.PROVISION, TENANT);
        var check2 = handler.check(testNode(), StepAction.PROVISION, TENANT);
        assertThat(check1).isInstanceOf(ApprovalCheckResult.Approved.class);
        assertThat(check2).isInstanceOf(ApprovalCheckResult.Approved.class);
    }

    // --- reject → check returns Rejected ---

    @Test
    void rejectTransitionsPendingToRejected() {
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "plan-ref-1");
        boolean result = handler.reject(NODE_1, StepAction.PROVISION, TENANT, "too risky");
        assertThat(result).isTrue();

        var check = handler.check(testNode(), StepAction.PROVISION, TENANT);
        assertThat(check).isInstanceOf(ApprovalCheckResult.Rejected.class);
        var rejected = (ApprovalCheckResult.Rejected) check;
        assertThat(rejected.reason()).isEqualTo("too risky");
    }

    // --- acknowledgeRejection removes entry and cleans up plan ---

    @Test
    void acknowledgeRejectionRemovesEntry() {
        String planRef = planStore.store(new ApprovalPlan(
                NODE_1, StepAction.PROVISION, RiskClassification.HIGH,
                "test", TENANT, new StubSpec(), null));
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, planRef);
        handler.reject(NODE_1, StepAction.PROVISION, TENANT, "too risky");

        handler.acknowledgeRejection(testNode(), StepAction.PROVISION, TENANT);

        assertThat(handler.check(testNode(), StepAction.PROVISION, TENANT))
                .isInstanceOf(ApprovalCheckResult.None.class);
        assertThat(planStore.retrieve(planRef)).isEmpty();
    }

    // --- precondition failures ---

    @Test
    void approveReturnsFalseWhenNoEntry() {
        boolean result = handler.approve(NODE_1, StepAction.PROVISION, TENANT, "admin");
        assertThat(result).isFalse();
    }

    @Test
    void approveReturnsFalseWhenAlreadyApproved() {
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "plan-ref-1");
        handler.approve(NODE_1, StepAction.PROVISION, TENANT, "admin");
        boolean result = handler.approve(NODE_1, StepAction.PROVISION, TENANT, "admin2");
        assertThat(result).isFalse();
    }

    @Test
    void rejectReturnsFalseWhenNoEntry() {
        boolean result = handler.reject(NODE_1, StepAction.PROVISION, TENANT, "reason");
        assertThat(result).isFalse();
    }

    @Test
    void rejectReturnsFalseWhenAlreadyRejected() {
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "plan-ref-1");
        handler.reject(NODE_1, StepAction.PROVISION, TENANT, "too risky");
        boolean result = handler.reject(NODE_1, StepAction.PROVISION, TENANT, "another reason");
        assertThat(result).isFalse();
    }

    @Test
    void approveReturnsFalseWhenRejected() {
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "plan-ref-1");
        handler.reject(NODE_1, StepAction.PROVISION, TENANT, "too risky");
        boolean result = handler.approve(NODE_1, StepAction.PROVISION, TENANT, "admin");
        assertThat(result).isFalse();
    }

    // --- recordPending supersedes stale entry ---

    @Test
    void recordPendingSupersedes() {
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "old-ref");
        handler.approve(NODE_1, StepAction.PROVISION, TENANT, "admin");
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "new-ref");

        var check = handler.check(testNode(), StepAction.PROVISION, TENANT);
        assertThat(check).isInstanceOf(ApprovalCheckResult.Pending.class);
        assertThat(((ApprovalCheckResult.Pending) check).planReference()).isEqualTo("new-ref");
    }

    // --- action isolation ---

    @Test
    void provisionAndDeprovisionAreIndependent() {
        handler.recordPending(testNode(), StepAction.PROVISION, TENANT, "prov-ref");
        handler.recordPending(testNode(), StepAction.DEPROVISION, TENANT, "deprov-ref");

        var provCheck = handler.check(testNode(), StepAction.PROVISION, TENANT);
        var deprovCheck = handler.check(testNode(), StepAction.DEPROVISION, TENANT);
        assertThat(((ApprovalCheckResult.Pending) provCheck).planReference()).isEqualTo("prov-ref");
        assertThat(((ApprovalCheckResult.Pending) deprovCheck).planReference()).isEqualTo("deprov-ref");
    }

    private record StubSpec() implements NodeSpec {}
}
