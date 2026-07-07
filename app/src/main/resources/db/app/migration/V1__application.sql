CREATE TABLE application (
    id              UUID            NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    tenancy_id      VARCHAR(100)    NOT NULL,
    services_json   TEXT            NOT NULL,
    compliance_policies_json TEXT,
    status          VARCHAR(30)     NOT NULL DEFAULT 'DRAFT',
    engine_case_id  UUID,
    created_at      TIMESTAMP       NOT NULL,
    updated_at      TIMESTAMP       NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_application_tenancy ON application(tenancy_id);
CREATE INDEX idx_application_status ON application(status);
