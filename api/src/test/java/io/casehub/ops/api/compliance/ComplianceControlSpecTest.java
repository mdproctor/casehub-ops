package io.casehub.ops.api.compliance;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ComplianceControlSpecTest {

    @Test
    void validSpecConstructs() {
        var spec = new ComplianceControlSpec(
                "encryption-at-rest", "ENCRYPTION_AT_REST",
                "Encryption at Rest", "AES-256 required",
                List.of(new FrameworkMapping("SOC2", "CC6.1")),
                30, false, Map.of("cipher", "AES-256"));
        assertThat(spec.controlId()).isEqualTo("encryption-at-rest");
        assertThat(spec.controlType()).isEqualTo("ENCRYPTION_AT_REST");
        assertThat(spec.evidenceMaxAgeDays()).isEqualTo(30);
        assertThat(spec.frameworks()).hasSize(1);
        assertThat(spec.properties()).containsEntry("cipher", "AES-256");
    }

    @Test
    void nullControlIdThrows() {
        assertThatThrownBy(() -> new ComplianceControlSpec(
                null, "TYPE", "T", "D", List.of(), 30, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("controlId");
    }

    @Test
    void blankControlTypeThrows() {
        assertThatThrownBy(() -> new ComplianceControlSpec(
                "id", "  ", "T", "D", List.of(), 30, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("controlType");
    }

    @Test
    void zeroEvidenceMaxAgeDaysThrows() {
        assertThatThrownBy(() -> new ComplianceControlSpec(
                "id", "TYPE", "T", "D", List.of(), 0, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceMaxAgeDays");
    }

    @Test
    void propertiesPreserveNullValues() {
        var props = new LinkedHashMap<String, Object>();
        props.put("key", null);
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D", List.of(), 30, false, props);
        assertThat(spec.properties()).containsEntry("key", null);
    }

    @Test
    void propertiesAreImmutable() {
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D", List.of(), 30, false, Map.of("k", "v"));
        assertThatThrownBy(() -> spec.properties().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void frameworksAreImmutable() {
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D",
                new java.util.ArrayList<>(List.of(new FrameworkMapping("SOC2", "CC6.1"))),
                30, false, Map.of());
        assertThatThrownBy(() -> spec.frameworks().add(new FrameworkMapping("GDPR", "Art.32")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullFrameworksDefaultsToEmptyList() {
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D", null, 30, false, null);
        assertThat(spec.frameworks()).isEmpty();
        assertThat(spec.properties()).isEmpty();
    }

    @Test
    void frameworkMappingValidation() {
        assertThatThrownBy(() -> new FrameworkMapping(null, "CC6.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrameworkMapping("SOC2", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evidenceResultCommonAccessor() {
        EvidenceResult pass = new EvidenceResult.Pass("ok");
        EvidenceResult fail = new EvidenceResult.Fail("bad");
        EvidenceResult unavail = new EvidenceResult.Unavailable("unreachable");
        assertThat(pass.detail()).isEqualTo("ok");
        assertThat(fail.detail()).isEqualTo("bad");
        assertThat(unavail.detail()).isEqualTo("unreachable");
    }

    @Test
    void goalEntryDefaultsDependsOnToEmpty() {
        var spec = new ComplianceControlSpec(
                "id", "TYPE", "T", "D", List.of(), 30, false, Map.of());
        var entry = new ComplianceGoalEntry(spec, null);
        assertThat(entry.dependsOn()).isEmpty();
    }

    @Test
    void goalsDefaultsControlsToEmpty() {
        var goals = new ComplianceGoals(null);
        assertThat(goals.controls()).isEmpty();
    }
}
