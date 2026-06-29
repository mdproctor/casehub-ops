package io.casehub.ops.deployment.adaptation;

import io.casehub.desiredstate.api.ActiveSituation;
import io.casehub.ops.api.deployment.DeploymentGoals;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-tenant mutable state for adaptive topology management.
 * <p>
 * Tracks the base deployment goals, parsed adaptation rules, and per-rule
 * hysteresis/cooldown state. Not thread-safe on its own — the caller
 * ({@link AdaptiveTopologyManager}) must synchronize access per tenant.
 */
final class TenantAdaptationState {

    private final DeploymentGoals goals;
    private final List<AdaptationRule> rules;
    private final Map<String, Boolean> activePerRule = new HashMap<>();
    private final Map<String, Instant> lastChangePerRule = new HashMap<>();

    TenantAdaptationState(DeploymentGoals goals, List<AdaptationRule> rules) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    DeploymentGoals goals() {
        return goals;
    }

    List<AdaptationRule> rules() {
        return rules;
    }

    /**
     * Determines whether a rule should be active given the current situation.
     * <p>
     * Implements hysteresis: when a rule is already active, it uses
     * {@code deactivateBelow} as the threshold instead of {@code minConfidence}.
     * This prevents churn when confidence oscillates near the activation threshold.
     * <p>
     * Implements cooldown: if a state change would occur but the time since the
     * last change is less than the cooldown duration, the current state is preserved.
     *
     * @param rule      the adaptation rule to evaluate
     * @param situation the active situation with current confidence
     * @return true if the rule should be active
     */
    boolean shouldActivate(AdaptationRule rule, ActiveSituation situation) {
        boolean currentlyActive = activePerRule.getOrDefault(rule.name(), false);

        double threshold = currentlyActive
            ? rule.trigger().effectiveDeactivateBelow()
            : rule.trigger().minConfidence();

        boolean shouldBeActive = situation.confidence() >= threshold;

        if (shouldBeActive != currentlyActive) {
            Duration cooldown = rule.trigger().effectiveCooldown();
            if (!cooldown.isZero()) {
                Instant lastChange = lastChangePerRule.get(rule.name());
                if (lastChange != null) {
                    Duration elapsed = Duration.between(lastChange, Instant.now());
                    if (elapsed.compareTo(cooldown) < 0) {
                        return currentlyActive;
                    }
                }
            }
        }

        if (shouldBeActive != currentlyActive) {
            lastChangePerRule.put(rule.name(), Instant.now());
            activePerRule.put(rule.name(), shouldBeActive);
        }

        return shouldBeActive;
    }

    /**
     * Resets activation state for rules whose trigger situation is no longer active.
     * <p>
     * When a situation disappears, the associated rules are deactivated so that
     * the next recompilation produces a graph without those adaptations. Cooldown
     * is respected — if the rule was recently activated and within the cooldown
     * window, it stays active until the cooldown expires.
     *
     * @param activeSituationIds the set of currently active situation IDs
     */
    void clearAbsentSituations(Set<String> activeSituationIds) {
        for (AdaptationRule rule : rules) {
            String sit = rule.trigger().situation();
            if (!activeSituationIds.contains(sit)) {
                Boolean wasActive = activePerRule.get(rule.name());
                if (wasActive != null && wasActive) {
                    Duration cooldown = rule.trigger().effectiveCooldown();
                    if (!cooldown.isZero()) {
                        Instant lastChange = lastChangePerRule.get(rule.name());
                        if (lastChange != null) {
                            Duration elapsed = Duration.between(lastChange, Instant.now());
                            if (elapsed.compareTo(cooldown) < 0) {
                                continue;
                            }
                        }
                    }
                    activePerRule.put(rule.name(), false);
                    lastChangePerRule.put(rule.name(), Instant.now());
                }
            }
        }
    }
}
