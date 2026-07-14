package io.casehub.ops.app.case_;

import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DriftClassifyWorkerTest {

    @Test
    void singleNodeFirstOccurrenceNonSecurityIsBenign() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 1,
                "driftDetails", List.of(Map.of("nodeId", "n1",
                        "fields", List.of(Map.of("fieldName", "replicas", "expectedValue", "3", "actualValue", "2")))));

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("benign");
    }

    @Test
    void persistentDriftIsCritical() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 3,
                "driftDetails", List.of(Map.of("nodeId", "n1", "fields", List.of())));

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("critical");
        assertThat((String) classification.get("reason")).contains("persistent");
    }

    @Test
    void securitySensitiveFieldIsCritical() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 1,
                "driftDetails", List.of(Map.of("nodeId", "n1",
                        "fields", List.of(Map.of("fieldName", "image", "expectedValue", "v1", "actualValue", "v2")))));

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("critical");
        assertThat((String) classification.get("reason")).contains("security");
    }

    @Test
    void multiNodeDriftIsCritical() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 1,
                "driftDetails", List.of(
                        Map.of("nodeId", "n1", "fields", List.of()),
                        Map.of("nodeId", "n2", "fields", List.of())));

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("critical");
    }

    @Test
    void emptyDetailsIsBenign() {
        var input = Map.<String, Object>of(
                "consecutiveDriftCount", 1,
                "driftDetails", List.of());

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("benign");
    }

    @Test
    void missingConsecutiveCountDefaultsToOne() {
        var input = Map.<String, Object>of(
                "driftDetails", List.of(Map.of("nodeId", "n1", "fields", List.of())));

        WorkerResult result = DriftRemediationCaseDescriptor.classifyDrift(input);

        @SuppressWarnings("unchecked")
        var classification = (Map<String, Object>) result.output().get("driftClassification");
        assertThat(classification.get("severity")).isEqualTo("benign");
    }

    @Test
    void remediateWorkerBenignReturnsAutoRemediating() {
        var input = Map.<String, Object>of(
                "driftClassification", Map.of("severity", "benign"));

        WorkerResult result = DriftRemediationCaseDescriptor.remediateDrift(input, noopTracker());
        assertThat(result.output().get("remediationStatus")).isEqualTo("auto-remediating");
    }

    @Test
    void remediateWorkerCriticalSetsEscalation() {
        var input = Map.<String, Object>of(
                "driftClassification", Map.of("severity", "critical"));

        WorkerResult result = DriftRemediationCaseDescriptor.remediateDrift(input, noopTracker());
        assertThat(result.output().get("escalationRequired")).isEqualTo(true);
    }

    @Test
    void remediateWorkerNullClassificationDefaultsBenign() {
        var input = Map.<String, Object>of();

        WorkerResult result = DriftRemediationCaseDescriptor.remediateDrift(input, noopTracker());
        assertThat(result.output().get("remediationStatus")).isEqualTo("auto-remediating");
    }

    @Test
    void escalateWorkerProducesEscalationOutput() {
        var input = Map.<String, Object>of(
                "driftClassification", Map.of(
                        "nodeIds", List.of("n1", "n2"),
                        "reason", "persistent drift"));

        WorkerResult result = DriftRemediationCaseDescriptor.escalateDrift(input);

        @SuppressWarnings("unchecked")
        var escalation = (Map<String, Object>) result.output().get("escalation");
        assertThat(escalation.get("risk")).isEqualTo("HIGH");
        assertThat((String) escalation.get("summary")).contains("2 node(s)");
    }

    @Test
    void escalateWorkerHandlesMissingClassification() {
        var input = Map.<String, Object>of();

        WorkerResult result = DriftRemediationCaseDescriptor.escalateDrift(input);

        @SuppressWarnings("unchecked")
        var escalation = (Map<String, Object>) result.output().get("escalation");
        assertThat(escalation).isNotNull();
        assertThat(escalation.get("risk")).isEqualTo("HIGH");
    }

    private static io.casehub.ops.app.service.NodeConvergenceTracker noopTracker() {
        return new io.casehub.ops.app.service.NodeConvergenceTracker(
                (caseId, path, value) -> {},
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    }
}
