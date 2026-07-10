package io.casehub.ops.app.model;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DriftReportTest {

    @Test
    void driftedNodeIdsExtractsFromDetails() {
        var report = new DriftReport(
                List.of(new NodeDrift("node-1", List.of()), new NodeDrift("node-2", List.of())),
                "cluster-1", "app-1", Instant.now(), 1);
        assertThat(report.driftedNodeIds()).containsExactly("node-1", "node-2");
    }

    @Test
    void emptyDetailsReturnsEmptyNodeIds() {
        var report = new DriftReport(List.of(), "c1", "a1", Instant.now(), 0);
        assertThat(report.driftedNodeIds()).isEmpty();
    }

    @Test
    void hasSecuritySensitiveFieldsDetectsImage() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of(new FieldDrift("image", "v1", "v2")))),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isTrue();
    }

    @Test
    void hasSecuritySensitiveFieldsDetectsServiceAccount() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of(new FieldDrift("serviceAccount", "sa1", "sa2")))),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isTrue();
    }

    @Test
    void hasSecuritySensitiveFieldsDetectsRbac() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of(new FieldDrift("rbac", "old", "new")))),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isTrue();
    }

    @Test
    void hasSecuritySensitiveFieldsDetectsSecrets() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of(new FieldDrift("secrets", "old", "new")))),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isTrue();
    }

    @Test
    void nonSecurityFieldsReturnsFalse() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of(new FieldDrift("replicas", "3", "2")))),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isFalse();
    }

    @Test
    void noFieldsReturnsFalse() {
        var report = new DriftReport(
                List.of(new NodeDrift("n1", List.of())),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isFalse();
    }

    @Test
    void multiNodeWithMixedFieldsSensitiveIfAnyMatch() {
        var report = new DriftReport(
                List.of(
                        new NodeDrift("n1", List.of(new FieldDrift("replicas", "3", "2"))),
                        new NodeDrift("n2", List.of(new FieldDrift("image", "v1", "v2")))),
                "c1", "a1", Instant.now(), 1);
        assertThat(report.hasSecuritySensitiveFields()).isTrue();
    }

    @Test
    void fieldDriftRecordFields() {
        var fd = new FieldDrift("replicas", "3", "2");
        assertThat(fd.fieldName()).isEqualTo("replicas");
        assertThat(fd.expectedValue()).isEqualTo("3");
        assertThat(fd.actualValue()).isEqualTo("2");
    }

    @Test
    void nodeDriftRecordFields() {
        var nd = new NodeDrift("n1", List.of(new FieldDrift("image", "a", "b")));
        assertThat(nd.nodeId()).isEqualTo("n1");
        assertThat(nd.fields()).hasSize(1);
    }
}
