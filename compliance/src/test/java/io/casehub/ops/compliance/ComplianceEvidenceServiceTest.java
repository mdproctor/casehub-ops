package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ops.api.compliance.*;
import io.casehub.platform.api.identity.ActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceEvidenceServiceTest {

    private ComplianceEvidenceService service;
    private StubLedgerRepository ledgerRepo;
    private StubEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        ledgerRepo = new StubLedgerRepository();
        collector = new StubEvidenceCollector(
                "LOG_RETENTION",
                new EvidenceResult.Pass("default pass"));
        service = new ComplianceEvidenceService(
                List.of(collector),
                ledgerRepo::save,
                ledgerRepo::findLatest);
    }

    @Test
    void collectAndRecord_withPassOutcome_writesLedgerEntryWithCorrectBaseFields() {
        collector.nextResult = new EvidenceResult.Pass("retention policy present");
        ComplianceControlSpec spec = new ComplianceControlSpec(
                "ctrl-001",
                "LOG_RETENTION",
                "Log Retention Policy",
                "Ensure all logs are retained for the required period",
                List.of(),
                30,
                false,
                null
        );

        EvidenceOutcome outcome = service.collectAndRecord(spec, "tenant-1");

        assertThat(outcome).isEqualTo(EvidenceOutcome.PASS);
        assertThat(ledgerRepo.saved).isNotNull();
        assertThat(ledgerRepo.saved.subjectId).isNotNull();
        assertThat(ledgerRepo.saved.entryType).isEqualTo(LedgerEntryType.EVENT);
        assertThat(ledgerRepo.saved.actorType).isEqualTo(ActorType.SYSTEM);
        assertThat(ledgerRepo.saved.actorId).isEqualTo("system:compliance-evidence");
        assertThat(ledgerRepo.saved.actorRole).isEqualTo("EvidenceCollector");
        assertThat(ledgerRepo.saved.occurredAt).isNotNull();
        assertThat(ledgerRepo.saved.controlId).isEqualTo("ctrl-001");
        assertThat(ledgerRepo.saved.controlType).isEqualTo("LOG_RETENTION");
        assertThat(ledgerRepo.saved.outcome).isEqualTo(EvidenceOutcome.PASS);
        assertThat(ledgerRepo.saved.detail).isEqualTo("retention policy present");
        assertThat(ledgerRepo.saved.tenancyId).isEqualTo("tenant-1");
    }

    @Test
    void collectAndRecord_withFailOutcome_mapsCorrectly() {
        collector.nextResult = new EvidenceResult.Fail("retention policy missing");
        ComplianceControlSpec spec = new ComplianceControlSpec(
                "ctrl-002",
                "LOG_RETENTION",
                "Log Retention Policy",
                "Ensure all logs are retained for the required period",
                List.of(),
                30,
                false,
                null
        );

        EvidenceOutcome outcome = service.collectAndRecord(spec, "tenant-1");

        assertThat(outcome).isEqualTo(EvidenceOutcome.FAIL);
        assertThat(ledgerRepo.saved.outcome).isEqualTo(EvidenceOutcome.FAIL);
        assertThat(ledgerRepo.saved.detail).isEqualTo("retention policy missing");
    }

    @Test
    void collectAndRecord_withUnavailableOutcome_mapsCorrectly() {
        collector.nextResult = new EvidenceResult.Unavailable("audit system offline");
        ComplianceControlSpec spec = new ComplianceControlSpec(
                "ctrl-003",
                "LOG_RETENTION",
                "Log Retention Policy",
                "Ensure all logs are retained for the required period",
                List.of(),
                30,
                false,
                null
        );

        EvidenceOutcome outcome = service.collectAndRecord(spec, "tenant-1");

        assertThat(outcome).isEqualTo(EvidenceOutcome.UNAVAILABLE);
        assertThat(ledgerRepo.saved.outcome).isEqualTo(EvidenceOutcome.UNAVAILABLE);
        assertThat(ledgerRepo.saved.detail).isEqualTo("audit system offline");
    }

    @Test
    void evidenceStatus_whenNoEvidence_returnsAbsent() {
        ledgerRepo.latestEntry = null;
        ComplianceControlSpec spec = new ComplianceControlSpec(
                "ctrl-004",
                "LOG_RETENTION",
                "Log Retention Policy",
                "Ensure all logs are retained for the required period",
                List.of(),
                30,
                false,
                null
        );

        ControlEvidenceStatus status = service.evidenceStatus(spec, "tenant-1");

        assertThat(status.controlId()).isEqualTo("ctrl-004");
        assertThat(status.controlType()).isEqualTo("LOG_RETENTION");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.ABSENT);
        assertThat(status.stale()).isFalse();
        assertThat(status.latestEvidenceAt()).isNull();
        assertThat(status.latestOutcome()).isNull();
    }

    @Test
    void evidenceStatus_whenFreshPass_returnsPresent() {
        ComplianceLedgerEntry entry = new ComplianceLedgerEntry();
        entry.outcome = EvidenceOutcome.PASS;
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.latestEntry = entry;

        ComplianceControlSpec spec = new ComplianceControlSpec(
                "ctrl-005",
                "LOG_RETENTION",
                "Log Retention Policy",
                "Ensure all logs are retained for the required period",
                List.of(),
                30,
                false,
                null
        );

        ControlEvidenceStatus status = service.evidenceStatus(spec, "tenant-1");

        assertThat(status.controlId()).isEqualTo("ctrl-005");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.PRESENT);
        assertThat(status.stale()).isFalse();
        assertThat(status.latestEvidenceAt()).isEqualTo(entry.occurredAt);
        assertThat(status.latestOutcome()).isEqualTo(EvidenceOutcome.PASS);
    }

    @Test
    void evidenceStatus_whenFreshFail_returnsDrifted() {
        ComplianceLedgerEntry entry = new ComplianceLedgerEntry();
        entry.outcome = EvidenceOutcome.FAIL;
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.latestEntry = entry;

        ComplianceControlSpec spec = new ComplianceControlSpec(
                "ctrl-006",
                "LOG_RETENTION",
                "Log Retention Policy",
                "Ensure all logs are retained for the required period",
                List.of(),
                30,
                false,
                null
        );

        ControlEvidenceStatus status = service.evidenceStatus(spec, "tenant-1");

        assertThat(status.controlId()).isEqualTo("ctrl-006");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.DRIFTED);
        assertThat(status.stale()).isFalse();
        assertThat(status.latestOutcome()).isEqualTo(EvidenceOutcome.FAIL);
    }

    @Test
    void evidenceStatus_whenFreshUnavailable_returnsDrifted() {
        ComplianceLedgerEntry entry = new ComplianceLedgerEntry();
        entry.outcome = EvidenceOutcome.UNAVAILABLE;
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.latestEntry = entry;

        ComplianceControlSpec spec = new ComplianceControlSpec(
                "ctrl-007",
                "LOG_RETENTION",
                "Log Retention Policy",
                "Ensure all logs are retained for the required period",
                List.of(),
                30,
                false,
                null
        );

        ControlEvidenceStatus status = service.evidenceStatus(spec, "tenant-1");

        assertThat(status.controlId()).isEqualTo("ctrl-007");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.DRIFTED);
        assertThat(status.stale()).isFalse();
        assertThat(status.latestOutcome()).isEqualTo(EvidenceOutcome.UNAVAILABLE);
    }

    @Test
    void evidenceStatus_whenStale_returnsDriftedStale() {
        ComplianceLedgerEntry entry = new ComplianceLedgerEntry();
        entry.outcome = EvidenceOutcome.PASS;
        // 60 days old
        entry.occurredAt = Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.latestEntry = entry;

        ComplianceControlSpec spec = new ComplianceControlSpec(
                "ctrl-008",
                "LOG_RETENTION",
                "Log Retention Policy",
                "Ensure all logs are retained for the required period",
                List.of(),
                30, // max age 30 days
                false,
                null
        );

        ControlEvidenceStatus status = service.evidenceStatus(spec, "tenant-1");

        assertThat(status.controlId()).isEqualTo("ctrl-008");
        assertThat(status.derivedNodeStatus()).isEqualTo(NodeStatus.DRIFTED);
        assertThat(status.stale()).isTrue();
        assertThat(status.latestOutcome()).isEqualTo(EvidenceOutcome.PASS);
    }

    // Stub implementations for testing
    static class StubEvidenceCollector implements EvidenceCollector {
        private final String type;
        EvidenceResult nextResult;

        StubEvidenceCollector(String type, EvidenceResult defaultResult) {
            this.type = type;
            this.nextResult = defaultResult;
        }

        @Override
        public String controlType() {
            return type;
        }

        @Override
        public EvidenceResult collect(ComplianceControlSpec spec, String tenancyId) {
            return nextResult;
        }
    }

    static class StubLedgerRepository {
        ComplianceLedgerEntry saved;
        ComplianceLedgerEntry latestEntry;

        void save(ComplianceLedgerEntry entry, String tenancyId) {
            entry.tenancyId = tenancyId;
            this.saved = entry;
        }

        List<ComplianceLedgerEntry> findLatest(String controlId, String tenancyId) {
            if (latestEntry == null) {
                return new ArrayList<>();
            }
            return List.of(latestEntry);
        }
    }
}
