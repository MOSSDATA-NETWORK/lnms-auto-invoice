CREATE TABLE audit_chain_heads (
    tenant_id uuid PRIMARY KEY REFERENCES tenants(id),
    last_event_hash char(64),
    last_event_id uuid REFERENCES audit_logs(id),
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO audit_chain_heads(tenant_id, last_event_hash, last_event_id, updated_at)
SELECT tenant_id, event_hash, id, created_at
FROM (
    SELECT DISTINCT ON (tenant_id) tenant_id, event_hash, id, created_at
    FROM audit_logs
    ORDER BY tenant_id, created_at DESC, id DESC
) latest;

CREATE UNIQUE INDEX uq_audit_logs_previous_hash
    ON audit_logs(tenant_id, previous_hash) NULLS NOT DISTINCT;

ALTER TABLE notification_logs
    ADD COLUMN send_started_at timestamptz;

ALTER TABLE notification_logs
    DROP CONSTRAINT notification_logs_status_check;

ALTER TABLE notification_logs
    ADD CONSTRAINT notification_logs_status_check
    CHECK (status IN (
        'PENDING', 'SENDING', 'SENT', 'RETRY', 'FAILED', 'DEAD', 'CANCELLED', 'UNCERTAIN'
    ));
