package io.casehub.ops.api.approval;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApprovalThresholdsTest {

    @Test
    void requiresApprovalAtThreshold() {
        var thresholds = new ApprovalThresholds(RiskClassification.HIGH);
        assertThat(thresholds.requiresApproval(RiskClassification.HIGH)).isTrue();
    }

    @Test
    void requiresApprovalAboveThreshold() {
        var thresholds = new ApprovalThresholds(RiskClassification.HIGH);
        assertThat(thresholds.requiresApproval(RiskClassification.CRITICAL)).isTrue();
    }

    @Test
    void doesNotRequireApprovalBelowThreshold() {
        var thresholds = new ApprovalThresholds(RiskClassification.HIGH);
        assertThat(thresholds.requiresApproval(RiskClassification.MEDIUM)).isFalse();
        assertThat(thresholds.requiresApproval(RiskClassification.LOW)).isFalse();
    }

    @Test
    void lowThresholdRequiresApprovalForEverything() {
        var thresholds = new ApprovalThresholds(RiskClassification.LOW);
        assertThat(thresholds.requiresApproval(RiskClassification.LOW)).isTrue();
        assertThat(thresholds.requiresApproval(RiskClassification.MEDIUM)).isTrue();
        assertThat(thresholds.requiresApproval(RiskClassification.HIGH)).isTrue();
        assertThat(thresholds.requiresApproval(RiskClassification.CRITICAL)).isTrue();
    }
}
