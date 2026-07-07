CREATE TABLE deployment_record (
    id              UUID            NOT NULL,
    application_id  UUID            NOT NULL,
    topology_json   TEXT            NOT NULL,
    trigger         VARCHAR(30)     NOT NULL,
    outcome         VARCHAR(30)     NOT NULL,
    created_at      TIMESTAMP       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_deployment_application
        FOREIGN KEY (application_id) REFERENCES application(id)
);

CREATE INDEX idx_deployment_application ON deployment_record(application_id);
CREATE INDEX idx_deployment_created ON deployment_record(created_at);
