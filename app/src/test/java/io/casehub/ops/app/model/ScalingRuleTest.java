package io.casehub.ops.app.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class ScalingRuleTest {

    @Test
    void confidenceAtMinReturnsMinReplicas() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.computeTarget(0.5)).isEqualTo(2);
    }

    @Test
    void confidenceAtOneReturnsMaxReplicas() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.computeTarget(1.0)).isEqualTo(10);
    }

    @Test
    void confidenceMidwayReturnsProportional() {
        var rule = new ScalingRule("high-load", 0.0, 2, 10, null);
        assertThat(rule.computeTarget(0.5)).isEqualTo(6);
    }

    @Test
    void confidenceBelowMinClampsToMinReplicas() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.computeTarget(0.3)).isEqualTo(2);
    }

    @Test
    void confidenceAboveOneClampsToMaxReplicas() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.computeTarget(1.5)).isEqualTo(10);
    }

    @Test
    void equalMinMaxReturnsConstant() {
        var rule = new ScalingRule("high-load", 0.5, 5, 5, null);
        assertThat(rule.computeTarget(0.5)).isEqualTo(5);
        assertThat(rule.computeTarget(1.0)).isEqualTo(5);
    }

    @Test
    void cooldownPeriodIsNullable() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, null);
        assertThat(rule.cooldownPeriod()).isNull();
    }

    @Test
    void cooldownPeriodIsStored() {
        var rule = new ScalingRule("high-load", 0.5, 2, 10, Duration.ofMinutes(5));
        assertThat(rule.cooldownPeriod()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsNullSituationId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ScalingRule(null, 0.5, 2, 10, null));
    }

    @Test
    void rejectsNegativeMinReplicas() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingRule("s", 0.5, -1, 10, null));
    }

    @Test
    void rejectsMaxLessThanMin() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingRule("s", 0.5, 10, 5, null));
    }

    @Test
    void rejectsMinConfidenceBelowZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingRule("s", -0.1, 2, 10, null));
    }

    @Test
    void rejectsMinConfidenceAboveOne() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingRule("s", 1.1, 2, 10, null));
    }

    @Test
    void minConfidenceAtOneDivisionByZeroHandled() {
        var rule = new ScalingRule("high-load", 1.0, 2, 10, null);
        assertThat(rule.computeTarget(1.0)).isEqualTo(2);
    }
}
