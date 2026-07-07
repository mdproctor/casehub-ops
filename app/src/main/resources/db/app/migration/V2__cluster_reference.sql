CREATE TABLE cluster_reference (
    id              UUID            NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    api_url         VARCHAR(1024)   NOT NULL,
    namespace       VARCHAR(255)    NOT NULL,
    credential_ref  VARCHAR(512),
    cluster_type    VARCHAR(30)     NOT NULL,
    status          VARCHAR(30)     NOT NULL DEFAULT 'UNKNOWN',
    tenancy_id      VARCHAR(100)    NOT NULL,
    created_at      TIMESTAMP       NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_cluster_tenancy ON cluster_reference(tenancy_id);
