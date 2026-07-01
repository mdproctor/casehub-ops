package io.casehub.ops.api.approval;

public record ApprovalThresholds(RiskClassification autoApproveBelow) {
    public boolean requiresApproval(RiskClassification risk) {
        return risk.compareTo(autoApproveBelow) >= 0;
    }
}
