CREATE TABLE webhook_endpoints (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    endpoint_code varchar(100) NOT NULL,
    endpoint_name varchar(240) NOT NULL,
    target_url varchar(1200) NOT NULL,
    signing_secret_ciphertext text NOT NULL,
    event_types_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'ERROR')),
    last_success_at timestamptz,
    last_failure_at timestamptz,
    consecutive_failures integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, endpoint_code)
);

CREATE INDEX idx_webhook_endpoints_active
    ON webhook_endpoints(tenant_id, status, endpoint_code);
