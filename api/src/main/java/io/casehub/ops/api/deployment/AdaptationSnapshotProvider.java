package io.casehub.ops.api.deployment;

import java.time.Instant;
import java.util.List;

public interface AdaptationSnapshotProvider {

    AdaptationSnapshot getAdaptationSnapshot(String tenancyId);

    record AdaptationSnapshot(
            List<ActiveRuleInfo> activeRules,
            List<TrackedSituationInfo> trackedSituations) {

        public AdaptationSnapshot {
            activeRules = List.copyOf(activeRules);
            trackedSituations = List.copyOf(trackedSituations);
        }
    }

    record ActiveRuleInfo(String ruleName, String situationId, boolean active) {}

    record TrackedSituationInfo(
            String situationId, double confidence,
            Instant since, Instant lastSignal) {}
}
