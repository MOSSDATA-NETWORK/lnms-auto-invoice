CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE customer_contacts ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE service_groups ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE service_resources ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE pricing_rules ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE invoice_templates ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE approval_workflows ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE invoice_profile_assignments ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE notification_templates ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE template_bindings ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

ALTER TABLE pricing_rule_versions
    ADD CONSTRAINT ex_published_pricing_version_period
    EXCLUDE USING gist (
        pricing_rule_id WITH =,
        tstzrange(effective_from, COALESCE(effective_to, 'infinity'::timestamptz), '[)') WITH &&
    ) WHERE (status = 'PUBLISHED')
    DEFERRABLE INITIALLY IMMEDIATE;

CREATE UNIQUE INDEX uq_active_librenms_bill_mapping
    ON librenms_bill_mappings(tenant_id, librenms_instance_id, librenms_bill_id)
    WHERE status = 'ACTIVE' AND effective_to IS NULL;

CREATE TABLE librenms_discovered_bills (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    librenms_instance_id uuid NOT NULL REFERENCES librenms_instances(id),
    librenms_bill_id bigint NOT NULL,
    bill_name varchar(500),
    bill_ref varchar(500),
    bill_custid varchar(500),
    bill_type varchar(100),
    bill_state varchar(100),
    source_payload_json jsonb NOT NULL,
    response_hash char(64) NOT NULL,
    discovered_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, librenms_instance_id, librenms_bill_id)
);

CREATE INDEX idx_librenms_discovered_bills_lookup
    ON librenms_discovered_bills(tenant_id, librenms_instance_id, bill_name);

CREATE TABLE payment_refunds (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    payment_id uuid NOT NULL REFERENCES payments(id),
    refund_number varchar(120) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    reason text NOT NULL,
    external_reference varchar(300),
    status varchar(24) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'VOIDED')),
    refunded_at timestamptz NOT NULL,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, refund_number)
);

CREATE UNIQUE INDEX uq_payment_refund_external_reference
    ON payment_refunds(tenant_id, external_reference)
    WHERE external_reference IS NOT NULL;

CREATE INDEX idx_payment_refunds_payment
    ON payment_refunds(tenant_id, payment_id, refunded_at DESC);

CREATE TABLE import_staging_rows (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    import_job_id uuid NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    row_number integer NOT NULL CHECK (row_number > 0),
    entity_type varchar(64) NOT NULL,
    row_data_json jsonb NOT NULL,
    row_hash char(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'VALID', 'INVALID', 'IMPORTED', 'SKIPPED')),
    imported_resource_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (import_job_id, row_number),
    UNIQUE (import_job_id, row_hash)
);

CREATE INDEX idx_import_staging_rows_status
    ON import_staging_rows(tenant_id, import_job_id, status, row_number);
