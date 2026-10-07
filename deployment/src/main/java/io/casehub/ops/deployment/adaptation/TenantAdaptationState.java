package io.casehub.ops.deployment.adaptation;

import io.casehub.ops.api.deployment.DeploymentGoals;
import io.casehub.ras.api.ActiveSituation;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class TenantAdaptationState {

    private final DeploymentGoals goals;
    private final List<AdaptationRule> rules;
    private final Map<String, Boolean> activePerRule = new HashMap<>();
    private final Map<String, Instant> lastChangePerRule = new HashMap<>();
    private final Map<String, ActiveSituation> trackedSituations = new HashMap<>();
    private final Map<String, Duration> situationClearanceWindows;

    TenantAdaptationState(DeploymentGoals goals, List<AdaptationRule> rules,
                          Map<String, Duration> situationClearanceWindows) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.situationClearanceWindows = Map.copyOf(
            Objects.requireNonNull(situationClearanceWindows, "situationClearanceWindows"));
    }

    TenantAdaptationState(DeploymentGoals goals, List<AdaptationRule> rules) {
        this(goals, rules, Map.of());
    }

    DeploymentGoals goals() {
        return goals;
    }

    List<AdaptationRule> rules() {
        return rules;
    }

    void updateSituation(ActiveSituation situation) {
        trackedSituations.put(situation.situationId(), situation);
    }

    Optional<ActiveSituation> activeSituationFor(AdaptationRule rule) {
        return Optional.ofNullable(
            trackedSituations.get(rule.trigger().situation()));
    }

    boolean clearSituation(String situationId) {
        ActiveSituation removed = trackedSituations.remove(situationId);
        if (removed == null) return false;
        for (AdaptationRule rule : rules) {
            if (rule.trigger().situation().equals(situationId)) {
                activePerRule.put(rule.name(), false);
                lastChangePerRule.put(rule.name(), Instant.now());
            }
        }
        return true;
    }

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


    boolean isRuleActive(String ruleName) {
        return activePerRule.getOrDefault(ruleName, false);
    }

    List<ActiveSituation> trackedSituationSnapshot() {
        return List.copyOf(trackedSituations.values());
    }


    void clearAbsentSituations() {
        Instant now = Instant.now();
        var iter = trackedSituations.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            String sitId = entry.getKey();
            ActiveSituation sit = entry.getValue();
            Duration clearanceWindow = situationClearanceWindows.getOrDefault(
                sitId, Duration.ofMinutes(5));
            if (Duration.between(sit.lastSignal(), now).compareTo(clearanceWindow) > 0) {
                for (AdaptationRule rule : rules) {
                    if (rule.trigger().situation().equals(sitId)) {
                        Boolean wasActive = activePerRule.get(rule.name());
                        if (wasActive != null && wasActive) {
                            Duration cooldown = rule.trigger().effectiveCooldown();
                            if (!cooldown.isZero()) {
                                Instant lastChange = lastChangePerRule.get(rule.name());
                                if (lastChange != null &&
                                    Duration.between(lastChange, now).compareTo(cooldown) < 0) {
                                    continue;
                                }
                            }
                            activePerRule.put(rule.name(), false);
                            lastChangePerRule.put(rule.name(), now);
                        }
                    }
                }
                iter.remove();
            }
        }
    }
}
