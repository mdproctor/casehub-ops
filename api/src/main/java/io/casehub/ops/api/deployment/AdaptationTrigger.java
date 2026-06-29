package io.casehub.ops.api.deployment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdaptationTrigger(
        String situation,
        double minConfidence,
        Double deactivateBelow,
        Duration cooldown
) {
    public AdaptationTrigger {
        Objects.requireNonNull(situation, "situation");
        if (Double.isNaN(minConfidence) || minConfidence < 0.0 || minConfidence >= 1.0) {
            throw new IllegalArgumentException(
                "minConfidence must be [0.0, 1.0), got: " + minConfidence);
        }
        if (deactivateBelow != null && (deactivateBelow < 0.0 || deactivateBelow > minConfidence)) {
            throw new IllegalArgumentException(
                "deactivateBelow must be [0.0, minConfidence], got: " + deactivateBelow);
        }
    }

    public double effectiveDeactivateBelow() {
        return deactivateBelow != null ? deactivateBelow : minConfidence;
    }

    public Duration effectiveCooldown() {
        return cooldown != null ? cooldown : Duration.ZERO;
    }
}
