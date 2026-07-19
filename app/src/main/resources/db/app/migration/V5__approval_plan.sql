CREATE TABLE approval_plan (
    ref             VARCHAR(36)     NOT NULL,
    node_id         VARCHAR(255)    NOT NULL,
    action          VARCHAR(30)     NOT NULL,
    risk            VARCHAR(30)     NOT NULL,
    tenancy_id      VARCHAR(100)    NOT NULL,
    plan_json       TEXT            NOT NULL,
    created_at      TIMESTAMP       NOT NULL,
    PRIMARY KEY (ref)
);

CREATE INDEX idx_approval_plan_tenancy ON approval_plan(tenancy_id);
CREATE INDEX idx_approval_plan_node ON approval_plan(node_id);
