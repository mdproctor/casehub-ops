package io.casehub.ops.app.case_;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ScalingPolicyTest {

    @Test
    void clampReturnsTargetWithinBounds() {
        var policy = new ScalingPolicy(2, 10, null);
        assertThat(policy.clamp(5)).isEqualTo(5);
    }

    @Test
    void clampClampsToMin() {
        var policy = new ScalingPolicy(3, 10, null);
        assertThat(policy.clamp(1)).isEqualTo(3);
    }

    @Test
    void clampClampsToMax() {
        var policy = new ScalingPolicy(1, 8, null);
        assertThat(policy.clamp(20)).isEqualTo(8);
    }

    @Test
    void clampAtBoundaryValues() {
        var policy = new ScalingPolicy(2, 10, null);
        assertThat(policy.clamp(2)).isEqualTo(2);
        assertThat(policy.clamp(10)).isEqualTo(10);
    }

    @Test
    void isCoolingDownReturnsFalseWhenNoCooldown() {
        var policy = new ScalingPolicy(1, 10, null);
        assertThat(policy.isCoolingDown(Instant.now(), Instant.now())).isFalse();
    }

    @Test
    void isCoolingDownReturnsFalseWhenNoLastEvent() {
        var policy = new ScalingPolicy(1, 10, Duration.ofMinutes(5));
        assertThat(policy.isCoolingDown(null, Instant.now())).isFalse();
    }

    @Test
    void isCoolingDownReturnsTrueWithinPeriod() {
        var policy = new ScalingPolicy(1, 10, Duration.ofMinutes(5));
        Instant now = Instant.now();
        Instant recentEvent = now.minus(Duration.ofMinutes(2));
        assertThat(policy.isCoolingDown(recentEvent, now)).isTrue();
    }

    @Test
    void isCoolingDownReturnsFalseAfterPeriod() {
        var policy = new ScalingPolicy(1, 10, Duration.ofMinutes(5));
        Instant now = Instant.now();
        Instant oldEvent = now.minus(Duration.ofMinutes(10));
        assertThat(policy.isCoolingDown(oldEvent, now)).isFalse();
    }

    @Test
    void isCoolingDownExactBoundaryIsNotCooling() {
        var policy = new ScalingPolicy(1, 10, Duration.ofMinutes(5));
        Instant now = Instant.now();
        Instant exactBoundary = now.minus(Duration.ofMinutes(5));
        assertThat(policy.isCoolingDown(exactBoundary, now)).isFalse();
    }

    @Test
    void constructorRejectsNegativeMin() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingPolicy(-1, 10, null));
    }

    @Test
    void constructorRejectsMaxLessThanMin() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingPolicy(5, 3, null));
    }

    @Test
    void constructorRejectsNegativeCooldown() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScalingPolicy(1, 10, Duration.ofMinutes(-1)));
    }

    @Test
    void unboundedPolicyDoesNotClamp() {
        assertThat(ScalingPolicy.UNBOUNDED.clamp(1)).isEqualTo(1);
        assertThat(ScalingPolicy.UNBOUNDED.clamp(10000)).isEqualTo(10000);
    }

    @Test
    void unboundedPolicyNeverCoolsDown() {
        assertThat(ScalingPolicy.UNBOUNDED.isCoolingDown(Instant.now(), Instant.now())).isFalse();
    }
}
