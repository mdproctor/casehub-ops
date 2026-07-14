package io.casehub.ops.app.case_;

import java.time.Duration;
import java.time.Instant;

public record ScalingPolicy(int minReplicas, int maxReplicas, Duration cooldownPeriod) {

    public ScalingPolicy {
        if (minReplicas < 0) throw new IllegalArgumentException("minReplicas must be >= 0");
        if (maxReplicas < minReplicas) throw new IllegalArgumentException("maxReplicas must be >= minReplicas");
        if (cooldownPeriod != null && cooldownPeriod.isNegative()) throw new IllegalArgumentException("cooldownPeriod must not be negative");
    }

    public static final ScalingPolicy UNBOUNDED = new ScalingPolicy(0, Integer.MAX_VALUE, null);

    public int clamp(int targetReplicas) {
        return Math.max(minReplicas, Math.min(maxReplicas, targetReplicas));
    }

    public boolean isCoolingDown(Instant lastScalingEvent, Instant now) {
        if (cooldownPeriod == null || lastScalingEvent == null) return false;
        return Duration.between(lastScalingEvent, now).compareTo(cooldownPeriod) < 0;
    }
}
