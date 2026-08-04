ALTER TABLE users
    ADD CONSTRAINT uq_users_tenant_id_id UNIQUE (tenant_id, id);

CREATE TABLE tenant_operational_settings (
    tenant_id uuid PRIMARY KEY REFERENCES tenants(id),
    system_user_id uuid,
    auto_generation_enabled boolean NOT NULL DEFAULT false,
    auto_send_enabled boolean NOT NULL DEFAULT false,
    emergency_stop boolean NOT NULL DEFAULT false,
    emergency_reason text,
    updated_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, system_user_id) REFERENCES users(tenant_id, id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES users(tenant_id, id),
    CHECK (NOT emergency_stop OR emergency_reason IS NOT NULL)
);

INSERT INTO tenant_operational_settings(tenant_id, system_user_id)
SELECT tenant.id,
       (SELECT app_user.id
        FROM users app_user
        WHERE app_user.tenant_id = tenant.id AND app_user.status = 'ACTIVE'
        ORDER BY app_user.created_at, app_user.id
        LIMIT 1)
FROM tenants tenant;

CREATE UNIQUE INDEX uq_invoice_single_replacement
    ON invoice_relations(tenant_id, source_invoice_id)
    WHERE relation_type = 'REPLACES';

CREATE INDEX idx_outbox_events_lease_expiry
    ON outbox_events(locked_until)
    WHERE status = 'PUBLISHING';
