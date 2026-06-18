CREATE TABLE compliance_evidence_entry (
    id          UUID NOT NULL,
    control_id  VARCHAR(255) NOT NULL,
    control_type VARCHAR(255) NOT NULL,
    evidence_outcome VARCHAR(20) NOT NULL,
    evidence_detail TEXT,
    collector_id VARCHAR(255),
    CONSTRAINT pk_compliance_evidence_entry PRIMARY KEY (id),
    CONSTRAINT fk_compliance_evidence_entry_ledger
        FOREIGN KEY (id) REFERENCES ledger_entry(id)
);

CREATE INDEX idx_compliance_evidence_control_id
    ON compliance_evidence_entry(control_id);
