package io.casehub.ops.api.approval;

public sealed interface ApprovalDecision {
    record AutoApproved() implements ApprovalDecision {}
    record RequiresApproval(ApprovalPlan plan) implements ApprovalDecision {}
}
