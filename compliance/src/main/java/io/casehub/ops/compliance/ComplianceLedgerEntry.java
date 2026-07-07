package io.casehub.ops.compliance;

import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ops.api.compliance.EvidenceOutcome;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;

@NamedQueries({
    @NamedQuery(
        name = "ComplianceLedgerEntry.findLatestByControlId",
        query = "SELECT e FROM ComplianceLedgerEntry e WHERE e.controlId = :controlId AND e.tenancyId = :tenancyId ORDER BY e.occurredAt DESC"
    ),
    @NamedQuery(
        name = "ComplianceLedgerEntry.findByControlId",
        query = "SELECT e FROM ComplianceLedgerEntry e WHERE e.controlId = :controlId AND e.tenancyId = :tenancyId ORDER BY e.occurredAt ASC"
    )
})
@Entity
@Table(name = "compliance_evidence_entry")
@DiscriminatorValue("COMPLIANCE_EVIDENCE")
public class ComplianceLedgerEntry extends LedgerEntry {

    @Column(name = "control_id", nullable = false)
    public String controlId;

    @Column(name = "control_type", nullable = false)
    public String controlType;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_outcome", nullable = false)
    public EvidenceOutcome outcome;

    @Column(name = "evidence_detail")
    public String detail;

    @Column(name = "collector_id")
    public String collectorId;

    @Override
    protected byte[] domainContentBytes() {
        String content = String.join("|",
                controlId != null ? controlId : "",
                controlType != null ? controlType : "",
                outcome != null ? outcome.name() : "",
                detail != null ? detail : "",
                collectorId != null ? collectorId : "");
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
