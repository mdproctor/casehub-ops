package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ops.api.compliance.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CompliancePostureServiceTest {

    private CompliancePostureService postureService;
    private ComplianceFrameworkRegistry registry;
    private StubLedgerRepository ledgerRepo;

    @BeforeEach
    void setUp() {
        registry = new ComplianceFrameworkRegistry();
        ledgerRepo = new StubLedgerRepository();
        ComplianceEvidenceService evidenceService = new ComplianceEvidenceService(
                List.of(),  // No collectors needed for evidence status testing
                (entry, tenancyId) -> {}, // No-op writer
                ledgerRepo::findLatest
        );
        postureService = new CompliancePostureService(registry, evidenceService);
    }

    @Test
    void emptyRegistryReturnsEmptyPosture() {
        FrameworkPosture posture = postureService.postureFor("SOC2", "default");
        assertThat(posture.totalControls()).isZero();
        assertThat(posture.complianceScore()).isEqualTo(0.0);
    }

    @Test
    void passingControlCountsAsCompliant() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1")));
        registry.register(spec);

        // Set up fresh PASS evidence for "enc"
        ComplianceLedgerEntry entry = new ComplianceLedgerEntry();
        entry.outcome = EvidenceOutcome.PASS;
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.setLatestEntry("enc", entry);

        FrameworkPosture posture = postureService.postureFor("SOC2", "default");
        assertThat(posture.totalControls()).isEqualTo(1);
        assertThat(posture.passingControls()).isEqualTo(1);
        assertThat(posture.failingControls()).isZero();
        assertThat(posture.unavailableControls()).isZero();
        assertThat(posture.staleControls()).isZero();
        assertThat(posture.missingControls()).isZero();
        assertThat(posture.complianceScore()).isEqualTo(1.0);
    }

    @Test
    void fiveCategoriesSumToTotal() {
        // Register 5 controls, each in different evidence state
        var passing = minimalSpec("ctrl-pass", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1")));
        var failing = minimalSpec("ctrl-fail", "LOG_RETENTION",
                List.of(new FrameworkMapping("SOC2", "CC7.2")));
        var unavailable = minimalSpec("ctrl-unavail", "ACCESS_CONTROL",
                List.of(new FrameworkMapping("SOC2", "CC6.2")));
        var stale = minimalSpec("ctrl-stale", "MFA_ENFORCEMENT",
                List.of(new FrameworkMapping("SOC2", "CC6.3")));
        var missing = minimalSpec("ctrl-missing", "BACKUP_VERIFICATION",
                List.of(new FrameworkMapping("SOC2", "CC7.3")));

        registry.register(passing);
        registry.register(failing);
        registry.register(unavailable);
        registry.register(stale);
        registry.register(missing);

        // Set up evidence states
        ComplianceLedgerEntry passEntry = new ComplianceLedgerEntry();
        passEntry.outcome = EvidenceOutcome.PASS;
        passEntry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.setLatestEntry("ctrl-pass", passEntry);

        ComplianceLedgerEntry failEntry = new ComplianceLedgerEntry();
        failEntry.outcome = EvidenceOutcome.FAIL;
        failEntry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.setLatestEntry("ctrl-fail", failEntry);

        ComplianceLedgerEntry unavailEntry = new ComplianceLedgerEntry();
        unavailEntry.outcome = EvidenceOutcome.UNAVAILABLE;
        unavailEntry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.setLatestEntry("ctrl-unavail", unavailEntry);

        ComplianceLedgerEntry staleEntry = new ComplianceLedgerEntry();
        staleEntry.outcome = EvidenceOutcome.PASS;
        staleEntry.occurredAt = Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.setLatestEntry("ctrl-stale", staleEntry);

        // ctrl-missing has no entry (absent)

        FrameworkPosture posture = postureService.postureFor("SOC2", "default");

        assertThat(posture.totalControls()).isEqualTo(5);
        assertThat(posture.passingControls()).isEqualTo(1);
        assertThat(posture.failingControls()).isEqualTo(1);
        assertThat(posture.unavailableControls()).isEqualTo(1);
        assertThat(posture.staleControls()).isEqualTo(1);
        assertThat(posture.missingControls()).isEqualTo(1);

        // Verify sum invariant
        int sum = posture.passingControls() + posture.failingControls() +
                  posture.unavailableControls() + posture.staleControls() +
                  posture.missingControls();
        assertThat(sum).isEqualTo(posture.totalControls());
    }

    @Test
    void postureForAllReturnsAllFrameworks() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"),
                        new FrameworkMapping("GDPR", "Art.32")));
        registry.register(spec);

        Map<String, FrameworkPosture> all = postureService.postureForAll("default");

        assertThat(all).containsKeys("SOC2", "GDPR");
        assertThat(all.get("SOC2").totalControls()).isEqualTo(1);
        assertThat(all.get("GDPR").totalControls()).isEqualTo(1);
    }

    @Test
    void controlAppearsInMultipleFrameworks() {
        var spec = minimalSpec("enc", "ENCRYPTION_AT_REST",
                List.of(new FrameworkMapping("SOC2", "CC6.1"),
                        new FrameworkMapping("GDPR", "Art.32(1)(a)")));
        registry.register(spec);

        // Set up fresh PASS evidence
        ComplianceLedgerEntry entry = new ComplianceLedgerEntry();
        entry.outcome = EvidenceOutcome.PASS;
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ledgerRepo.setLatestEntry("enc", entry);

        FrameworkPosture soc2 = postureService.postureFor("SOC2", "default");
        FrameworkPosture gdpr = postureService.postureFor("GDPR", "default");

        // Control appears in both frameworks
        assertThat(soc2.totalControls()).isEqualTo(1);
        assertThat(gdpr.totalControls()).isEqualTo(1);

        // Both show it as passing
        assertThat(soc2.passingControls()).isEqualTo(1);
        assertThat(gdpr.passingControls()).isEqualTo(1);

        // Requirements are framework-specific
        assertThat(soc2.controls().get(0).requirement()).isEqualTo("CC6.1");
        assertThat(gdpr.controls().get(0).requirement()).isEqualTo("Art.32(1)(a)");
    }

    private ComplianceControlSpec minimalSpec(String id, String type, List<FrameworkMapping> frameworks) {
        return new ComplianceControlSpec(id, type, "T", "D", frameworks, 30, false, Map.of());
    }

    // Stub that maintains a map of controlId -> entry
    static class StubLedgerRepository {
        private final Map<String, ComplianceLedgerEntry> entries = new HashMap<>();

        void setLatestEntry(String controlId, ComplianceLedgerEntry entry) {
            entries.put(controlId, entry);
        }

        List<ComplianceLedgerEntry> findLatest(String controlId, String tenancyId) {
            ComplianceLedgerEntry entry = entries.get(controlId);
            if (entry == null) {
                return new ArrayList<>();
            }
            return List.of(entry);
        }
    }
}
