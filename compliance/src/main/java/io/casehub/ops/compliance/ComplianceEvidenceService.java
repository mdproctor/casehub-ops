package io.casehub.ops.compliance;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.ops.api.compliance.*;
import io.casehub.platform.api.identity.ActorType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class ComplianceEvidenceService {

    @FunctionalInterface
    interface LedgerWriter {
        void save(ComplianceLedgerEntry entry, String tenancyId);
    }

    @FunctionalInterface
    interface LatestEvidenceFinder {
        List<ComplianceLedgerEntry> findLatest(String controlId, String tenancyId);
    }

    private final Map<String, EvidenceCollector> collectors;
    private final LedgerWriter ledgerWriter;
    private final LatestEvidenceFinder latestEvidenceFinder;

    @Inject
    public ComplianceEvidenceService(
            Instance<EvidenceCollector> collectorInstances,
            LedgerEntryRepository ledgerRepository,
            EntityManager entityManager) {
        this.collectors = new HashMap<>();
        StreamSupport.stream(collectorInstances.spliterator(), false)
                .forEach(c -> collectors.put(c.strategy(), c));
        this.ledgerWriter = (entry, tenancyId) -> {
            entry.tenancyId = tenancyId;
            ledgerRepository.save(entry, tenancyId);
        };
        this.latestEvidenceFinder = (controlId, tenancyId) ->
                entityManager.createNamedQuery("ComplianceLedgerEntry.findLatestByControlId", ComplianceLedgerEntry.class)
                        .setParameter("controlId", controlId)
                        .setParameter("tenancyId", tenancyId)
                        .setMaxResults(1)
                        .getResultList();
    }

    public ComplianceEvidenceService(
            List<EvidenceCollector> collectorList,
            LedgerWriter ledgerWriter,
            LatestEvidenceFinder latestEvidenceFinder) {
        this.collectors = new HashMap<>();
        collectorList.forEach(c -> collectors.put(c.strategy(), c));
        this.ledgerWriter = ledgerWriter;
        this.latestEvidenceFinder = latestEvidenceFinder;
    }

    public EvidenceOutcome collectAndRecord(ComplianceControlSpec spec, String tenancyId) {
        EvidenceCollector collector = collectors.get(spec.strategy());
        if (collector == null) {
            throw new IllegalArgumentException(
                    "No EvidenceCollector registered for strategy: " + spec.strategy());
        }

        EvidenceResult result;
        try {
            result = collector.collect(spec, tenancyId);
        } catch (Exception e) {
            result = new EvidenceResult.Unavailable("collector error: " + e.getMessage());
        }
        ComplianceLedgerEntry entry = createLedgerEntry(spec, result);

        ledgerWriter.save(entry, tenancyId);

        return mapToOutcome(result);
    }

    public Optional<ComplianceLedgerEntry> latestEvidence(String controlId, String tenancyId) {
        List<ComplianceLedgerEntry> results = latestEvidenceFinder.findLatest(controlId, tenancyId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public ControlEvidenceStatus evidenceStatus(ComplianceControlSpec spec, String tenancyId) {
        Optional<ComplianceLedgerEntry> latest = latestEvidence(spec.controlId(), tenancyId);

        if (latest.isEmpty()) {
            return ControlEvidenceStatus.absent(
                    spec.controlId(),
                    spec.controlType(),
                    spec.evidenceMaxAgeDays()
            );
        }

        ComplianceLedgerEntry entry = latest.get();
        boolean stale = isStale(entry, spec.evidenceMaxAgeDays());

        NodeStatus derivedStatus;
        if (stale) {
            derivedStatus = NodeStatus.DRIFTED;
        } else if (entry.outcome == EvidenceOutcome.PASS) {
            derivedStatus = NodeStatus.PRESENT;
        } else {
            // FAIL or UNAVAILABLE → DRIFTED
            derivedStatus = NodeStatus.DRIFTED;
        }

        return new ControlEvidenceStatus(
                spec.controlId(),
                spec.controlType(),
                entry.outcome,
                entry.occurredAt,
                spec.evidenceMaxAgeDays(),
                stale,
                derivedStatus
        );
    }

    private ComplianceLedgerEntry createLedgerEntry(ComplianceControlSpec spec, EvidenceResult result) {
        ComplianceLedgerEntry entry = new ComplianceLedgerEntry();
        entry.subjectId = deriveDeterministicSubjectId(spec.controlId());
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorType = ActorType.SYSTEM;
        entry.actorId = "system:compliance-evidence";
        entry.actorRole = "EvidenceCollector";
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        entry.controlId = spec.controlId();
        entry.controlType = spec.controlType();
        entry.outcome = mapToOutcome(result);
        entry.detail = result.detail();
        entry.collectorId = "system:" + spec.strategy().toLowerCase().replace('_', '-');
        return entry;
    }

    private static UUID deriveDeterministicSubjectId(String controlId) {
        return UUID.nameUUIDFromBytes(
                ("compliance:" + controlId).getBytes(StandardCharsets.UTF_8));
    }

    private EvidenceOutcome mapToOutcome(EvidenceResult result) {
        return switch (result) {
            case EvidenceResult.Pass p -> EvidenceOutcome.PASS;
            case EvidenceResult.Fail f -> EvidenceOutcome.FAIL;
            case EvidenceResult.Unavailable u -> EvidenceOutcome.UNAVAILABLE;
        };
    }

    private boolean isStale(ComplianceLedgerEntry entry, int evidenceMaxAgeDays) {
        Instant now = Instant.now();
        Duration age = Duration.between(entry.occurredAt, now);
        return age.toDays() > evidenceMaxAgeDays;
    }
}
