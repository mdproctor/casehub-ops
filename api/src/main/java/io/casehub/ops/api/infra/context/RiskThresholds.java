package io.casehub.ops.api.infra.context;

public record RiskThresholds(RiskClassification autoApproveBelow, boolean requireSecondReviewer) {
}
