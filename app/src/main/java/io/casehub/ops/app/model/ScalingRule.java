package io.casehub.ops.app.model;

import java.time.Duration;
import java.util.Objects;

public record ScalingRule(
        String situationId,
        double minConfidence,
        int minReplicas,
        int maxReplicas,
        Duration cooldownPeriod) {

    public ScalingRule {
        Objects.requireNonNull(situationId, "situationId");
        if (minConfidence < 0.0 || minConfidence > 1.0)
            throw new IllegalArgumentException("minConfidence must be 0.0-1.0, got: " + minConfidence);
        if (minReplicas < 0) throw new IllegalArgumentException("minReplicas must be >= 0");
        if (maxReplicas < minReplicas) throw new IllegalArgumentException("maxReplicas must be >= minReplicas");
    }

    public int computeTarget(double confidence) {
        double denominator = 1.0 - minConfidence;
        if (denominator <= 0.0) return minReplicas;
        double effective = Math.max(0.0, Math.min(1.0,
                (confidence - minConfidence) / denominator));
        return Math.max(minReplicas, Math.min(maxReplicas,
                minReplicas + (int) ((maxReplicas - minReplicas) * effective)));
    }
}
