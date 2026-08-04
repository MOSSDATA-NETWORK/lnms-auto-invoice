CREATE TABLE invoice_batches (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    batch_key varchar(200) NOT NULL,
    batch_name varchar(240) NOT NULL,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    trigger_type varchar(24) NOT NULL
        CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'IMPORT', 'RETRY')),
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'PARTIAL', 'SUCCESS', 'FAILED', 'CANCELLED')),
    profile_count integer NOT NULL DEFAULT 0 CHECK (profile_count >= 0),
    success_count integer NOT NULL DEFAULT 0 CHECK (success_count >= 0),
    failure_count integer NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    requested_by uuid REFERENCES users(id),
    started_at timestamptz,
    completed_at timestamptz,
    error_summary_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end > period_start),
    UNIQUE (tenant_id, batch_key)
);

CREATE INDEX idx_invoice_batches_status
    ON invoice_batches(tenant_id, status, created_at DESC);

CREATE TABLE invoice_previews (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    preview_number varchar(160) NOT NULL,
    invoice_batch_id uuid REFERENCES invoice_batches(id),
    invoice_profile_id uuid NOT NULL REFERENCES invoice_profiles(id),
    customer_id uuid NOT NULL REFERENCES customers(id),
    company_id uuid NOT NULL REFERENCES companies(id),
    template_id uuid NOT NULL REFERENCES invoice_templates(id),
    template_version_id uuid NOT NULL REFERENCES invoice_template_versions(id),
    approval_workflow_version_id uuid REFERENCES approval_workflow_versions(id),
    origin_invoice_id uuid,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    issue_date date NOT NULL,
    due_date date NOT NULL,
    timezone varchar(64) NOT NULL,
    language varchar(16) NOT NULL,
    currency_code char(3) NOT NULL REFERENCES currencies(code),
    exchange_rate numeric(30,12) NOT NULL DEFAULT 1 CHECK (exchange_rate > 0),
    subtotal_minor bigint NOT NULL DEFAULT 0,
    discount_minor bigint NOT NULL DEFAULT 0,
    tax_minor bigint NOT NULL DEFAULT 0,
    adjustment_minor bigint NOT NULL DEFAULT 0,
    total_minor bigint NOT NULL DEFAULT 0 CHECK (total_minor >= 0),
    profile_snapshot_json jsonb NOT NULL,
    party_snapshot_json jsonb NOT NULL,
    render_model_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    anomaly_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    calculation_hash char(64),
    status varchar(32) NOT NULL DEFAULT 'GENERATING'
        CHECK (status IN (
            'GENERATING', 'DRAFT', 'BUSINESS_REVIEW', 'FINANCE_REVIEW',
            'APPROVED', 'FINALIZING', 'FINALIZED', 'REJECTED', 'ERROR', 'VOIDED'
        )),
    approval_revision bigint NOT NULL DEFAULT 0 CHECK (approval_revision >= 0),
    generated_at timestamptz,
    approved_at timestamptz,
    finalized_at timestamptz,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (period_end > period_start),
    CHECK (due_date >= issue_date),
    UNIQUE (tenant_id, preview_number)
);

CREATE INDEX idx_invoice_previews_profile_period
    ON invoice_previews(tenant_id, invoice_profile_id, period_start, period_end);

CREATE INDEX idx_invoice_previews_queue
    ON invoice_previews(tenant_id, status, created_at DESC);

CREATE INDEX idx_invoice_previews_customer
    ON invoice_previews(tenant_id, customer_id, company_id, issue_date DESC);

CREATE TABLE invoice_preview_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    invoice_preview_id uuid NOT NULL REFERENCES invoice_previews(id) ON DELETE CASCADE,
    contract_item_id uuid REFERENCES contract_items(id),
    service_id uuid REFERENCES services(id),
    pricing_rule_version_id uuid REFERENCES pricing_rule_versions(id),
    usage_snapshot_id uuid REFERENCES usage_snapshots(id),
    source_key varchar(300) NOT NULL,
    line_no integer NOT NULL CHECK (line_no > 0),
    item_name varchar(500) NOT NULL,
    item_description text,
    billing_period_start timestamptz NOT NULL,
    billing_period_end timestamptz NOT NULL,
    raw_usage numeric(30,12),
    converted_usage numeric(30,12),
    rounded_usage numeric(30,12),
    billing_usage numeric(30,12),
    quantity numeric(30,12),
    unit varchar(48),
    unit_price numeric(30,12),
    subtotal_minor bigint NOT NULL DEFAULT 0,
    discount_minor bigint NOT NULL DEFAULT 0,
    tax_minor bigint NOT NULL DEFAULT 0,
    total_minor bigint NOT NULL DEFAULT 0,
    calculation_snapshot_json jsonb NOT NULL,
    display_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (billing_period_end > billing_period_start),
    UNIQUE (invoice_preview_id, line_no),
    UNIQUE (invoice_preview_id, source_key)
);

CREATE INDEX idx_preview_items_contract_item
    ON invoice_preview_items(tenant_id, contract_item_id, billing_period_start);

CREATE TABLE invoice_preview_adjustments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    invoice_preview_id uuid NOT NULL REFERENCES invoice_previews(id) ON DELETE CASCADE,
    adjustment_type varchar(32) NOT NULL
        CHECK (adjustment_type IN (
            'SURCHARGE', 'DISCOUNT', 'SLA_CREDIT', 'BALANCE_CREDIT', 'CARRY_FORWARD',
            'LATE_FEE', 'PRICE_CORRECTION', 'INSTALLATION', 'TEMP_BANDWIDTH',
            'EXCHANGE_RATE', 'TAX_CORRECTION', 'CUSTOM'
        )),
    description varchar(500) NOT NULL,
    amount_minor bigint NOT NULL,
    tax_rate numeric(12,8) CHECK (tax_rate BETWEEN 0 AND 1),
    included_in_tax_base boolean NOT NULL DEFAULT true,
    reason text NOT NULL,
    attachment_file_id uuid REFERENCES files(id),
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'REMOVED')),
    created_by uuid NOT NULL REFERENCES users(id),
    approved_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz
);

CREATE INDEX idx_preview_adjustments_active
    ON invoice_preview_adjustments(tenant_id, invoice_preview_id, created_at)
    WHERE status = 'ACTIVE';

CREATE TABLE invoice_preview_exclusions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    invoice_preview_id uuid NOT NULL REFERENCES invoice_previews(id) ON DELETE CASCADE,
    invoice_preview_item_id uuid NOT NULL REFERENCES invoice_preview_items(id) ON DELETE CASCADE,
    reason text NOT NULL,
    created_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (invoice_preview_id, invoice_preview_item_id)
);

CREATE TABLE approval_instances (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    invoice_preview_id uuid NOT NULL REFERENCES invoice_previews(id) ON DELETE CASCADE,
    workflow_version_id uuid NOT NULL REFERENCES approval_workflow_versions(id),
    preview_version bigint NOT NULL,
    approval_revision bigint NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'INVALIDATED')),
    current_step_no integer,
    requested_by uuid NOT NULL REFERENCES users(id),
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    invalidation_reason text,
    UNIQUE (invoice_preview_id, approval_revision)
);

CREATE INDEX idx_approval_instances_pending
    ON approval_instances(tenant_id, status, requested_at)
    WHERE status = 'PENDING';

CREATE TABLE approval_actions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    approval_instance_id uuid NOT NULL REFERENCES approval_instances(id) ON DELETE CASCADE,
    approval_step_id uuid REFERENCES approval_steps(id),
    preview_version bigint NOT NULL,
    action varchar(24) NOT NULL
        CHECK (action IN ('SUBMIT', 'APPROVE', 'REJECT', 'COMMENT', 'CANCEL', 'INVALIDATE')),
    actor_id uuid REFERENCES users(id),
    actor_snapshot_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    comment text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_approval_actions_instance
    ON approval_actions(tenant_id, approval_instance_id, created_at);

CREATE TABLE invoices (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    invoice_number varchar(180) NOT NULL,
    source_preview_id uuid NOT NULL REFERENCES invoice_previews(id),
    invoice_profile_id uuid NOT NULL REFERENCES invoice_profiles(id),
    customer_id uuid NOT NULL REFERENCES customers(id),
    company_id uuid NOT NULL REFERENCES companies(id),
    template_id uuid NOT NULL REFERENCES invoice_templates(id),
    template_version_id uuid NOT NULL REFERENCES invoice_template_versions(id),
    approval_instance_id uuid NOT NULL REFERENCES approval_instances(id),
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    issue_date date NOT NULL,
    due_date date NOT NULL,
    timezone varchar(64) NOT NULL,
    language varchar(16) NOT NULL,
    currency_code char(3) NOT NULL REFERENCES currencies(code),
    exchange_rate numeric(30,12) NOT NULL DEFAULT 1 CHECK (exchange_rate > 0),
    subtotal_minor bigint NOT NULL,
    discount_minor bigint NOT NULL,
    tax_minor bigint NOT NULL,
    adjustment_minor bigint NOT NULL,
    total_minor bigint NOT NULL CHECK (total_minor >= 0),
    party_snapshot_json jsonb NOT NULL,
    profile_snapshot_json jsonb NOT NULL,
    render_model_json jsonb NOT NULL,
    data_snapshot_hash char(64) NOT NULL,
    document_status varchar(24) NOT NULL DEFAULT 'FINALIZING'
        CHECK (document_status IN ('FINALIZING', 'CONFIRMED', 'SENT', 'VOIDED', 'REPLACED')),
    send_status varchar(24) NOT NULL DEFAULT 'NOT_QUEUED'
        CHECK (send_status IN ('NOT_QUEUED', 'QUEUED', 'PARTIALLY_SENT', 'SENT', 'FAILED')),
    payment_status varchar(24) NOT NULL DEFAULT 'UNPAID'
        CHECK (payment_status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID', 'OVERDUE')),
    finalized_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    sent_at timestamptz,
    voided_at timestamptz,
    paid_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CHECK (period_end > period_start),
    CHECK (due_date >= issue_date),
    UNIQUE (tenant_id, invoice_number),
    UNIQUE (tenant_id, source_preview_id)
);

ALTER TABLE invoice_previews
    ADD CONSTRAINT fk_preview_origin_invoice
    FOREIGN KEY (origin_invoice_id) REFERENCES invoices(id);

CREATE INDEX idx_invoices_customer_period
    ON invoices(tenant_id, customer_id, company_id, period_start, period_end);

CREATE INDEX idx_invoices_document_queue
    ON invoices(tenant_id, document_status, created_at DESC);

CREATE INDEX idx_invoices_payment_queue
    ON invoices(tenant_id, payment_status, due_date);

CREATE TABLE invoice_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    invoice_id uuid NOT NULL REFERENCES invoices(id),
    source_preview_item_id uuid REFERENCES invoice_preview_items(id),
    contract_item_id uuid REFERENCES contract_items(id),
    service_id uuid REFERENCES services(id),
    pricing_rule_version_id uuid REFERENCES pricing_rule_versions(id),
    usage_snapshot_id uuid REFERENCES usage_snapshots(id),
    source_key varchar(300) NOT NULL,
    line_no integer NOT NULL CHECK (line_no > 0),
    item_name varchar(500) NOT NULL,
    item_description text,
    billing_period_start timestamptz NOT NULL,
    billing_period_end timestamptz NOT NULL,
    raw_usage numeric(30,12),
    converted_usage numeric(30,12),
    rounded_usage numeric(30,12),
    billing_usage numeric(30,12),
    quantity numeric(30,12),
    unit varchar(48),
    unit_price numeric(30,12),
    subtotal_minor bigint NOT NULL,
    discount_minor bigint NOT NULL,
    tax_minor bigint NOT NULL,
    total_minor bigint NOT NULL,
    calculation_snapshot_json jsonb NOT NULL,
    display_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (billing_period_end > billing_period_start),
    UNIQUE (invoice_id, line_no),
    UNIQUE (invoice_id, source_key)
);

CREATE INDEX idx_invoice_items_contract_item
    ON invoice_items(tenant_id, contract_item_id, billing_period_start);

CREATE TABLE invoice_adjustments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    invoice_id uuid NOT NULL REFERENCES invoices(id),
    source_preview_adjustment_id uuid REFERENCES invoice_preview_adjustments(id),
    adjustment_type varchar(32) NOT NULL,
    description varchar(500) NOT NULL,
    amount_minor bigint NOT NULL,
    tax_rate numeric(12,8) CHECK (tax_rate BETWEEN 0 AND 1),
    included_in_tax_base boolean NOT NULL,
    reason text NOT NULL,
    attachment_file_id uuid REFERENCES files(id),
    operator_snapshot_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE invoice_files (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    invoice_id uuid NOT NULL REFERENCES invoices(id),
    file_id uuid NOT NULL REFERENCES files(id),
    file_role varchar(32) NOT NULL
        CHECK (file_role IN ('PDF', 'RENDER_MODEL', 'HTML_ARCHIVE', 'SUPPORTING_ATTACHMENT')),
    template_version_id uuid REFERENCES invoice_template_versions(id),
    renderer_version varchar(160),
    chromium_version varchar(160),
    font_manifest_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    content_sha256 char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (invoice_id, file_role, file_id)
);

CREATE INDEX idx_invoice_files_role
    ON invoice_files(tenant_id, invoice_id, file_role);

CREATE TABLE invoice_relations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    source_invoice_id uuid NOT NULL REFERENCES invoices(id),
    target_invoice_id uuid NOT NULL REFERENCES invoices(id),
    relation_type varchar(24) NOT NULL
        CHECK (relation_type IN ('REPLACES', 'CORRECTS', 'VOIDED_BY', 'CREDITED_BY')),
    reason text,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (source_invoice_id <> target_invoice_id),
    UNIQUE (source_invoice_id, target_invoice_id, relation_type)
);

CREATE TABLE payments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    payment_number varchar(120) NOT NULL,
    customer_id uuid NOT NULL REFERENCES customers(id),
    company_id uuid REFERENCES companies(id),
    currency_code char(3) NOT NULL REFERENCES currencies(code),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    payment_method varchar(48) NOT NULL,
    source_system varchar(100) NOT NULL DEFAULT 'MANUAL',
    external_reference varchar(300),
    receiving_account_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    payer_snapshot_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    paid_at timestamptz NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (status IN (
            'PENDING', 'CONFIRMED', 'PARTIALLY_ALLOCATED', 'ALLOCATED',
            'REFUND_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED'
        )),
    attachment_file_id uuid REFERENCES files(id),
    notes text,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, payment_number)
);

CREATE UNIQUE INDEX uq_payment_external_reference
    ON payments(tenant_id, source_system, external_reference)
    WHERE external_reference IS NOT NULL;

CREATE INDEX idx_payments_customer
    ON payments(tenant_id, customer_id, paid_at DESC);

CREATE TABLE payment_allocations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    payment_id uuid NOT NULL REFERENCES payments(id),
    invoice_id uuid NOT NULL REFERENCES invoices(id),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'REVERSED')),
    allocated_by uuid REFERENCES users(id),
    allocated_at timestamptz NOT NULL DEFAULT now(),
    reversed_by uuid REFERENCES users(id),
    reversed_at timestamptz,
    reversal_reason text
);

CREATE UNIQUE INDEX uq_active_payment_allocation
    ON payment_allocations(tenant_id, payment_id, invoice_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_payment_allocations_invoice
    ON payment_allocations(tenant_id, invoice_id, status);

CREATE TABLE notification_templates (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    template_code varchar(120) NOT NULL,
    template_name varchar(240) NOT NULL,
    channel varchar(24) NOT NULL
        CHECK (channel IN ('EMAIL', 'WECOM', 'TELEGRAM', 'SMS', 'WEBHOOK', 'PORTAL')),
    event_type varchar(100) NOT NULL,
    language varchar(16) NOT NULL DEFAULT 'zh-CN',
    subject_template text,
    body_template text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, template_code)
);

CREATE TABLE notification_logs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    notification_template_id uuid REFERENCES notification_templates(id),
    invoice_id uuid REFERENCES invoices(id),
    payment_id uuid REFERENCES payments(id),
    channel varchar(24) NOT NULL,
    event_type varchar(100) NOT NULL,
    recipient varchar(1000) NOT NULL,
    deduplication_key varchar(300) NOT NULL,
    subject_rendered text,
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'RETRY', 'FAILED', 'DEAD', 'CANCELLED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz,
    provider_message_id varchar(500),
    last_error_code varchar(100),
    last_error_message text,
    sent_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, deduplication_key)
);

CREATE INDEX idx_notification_logs_claim
    ON notification_logs(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RETRY');

CREATE TABLE import_jobs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    import_type varchar(48) NOT NULL,
    source_file_id uuid NOT NULL REFERENCES files(id),
    idempotency_key varchar(200) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'UPLOADED'
        CHECK (status IN ('UPLOADED', 'VALIDATING', 'READY', 'IMPORTING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED')),
    total_rows integer NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    valid_rows integer NOT NULL DEFAULT 0 CHECK (valid_rows >= 0),
    invalid_rows integer NOT NULL DEFAULT 0 CHECK (invalid_rows >= 0),
    imported_rows integer NOT NULL DEFAULT 0 CHECK (imported_rows >= 0),
    error_file_id uuid REFERENCES files(id),
    options_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    requested_by uuid REFERENCES users(id),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE import_row_errors (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    import_job_id uuid NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    row_number integer NOT NULL CHECK (row_number > 0),
    field_name varchar(200),
    error_code varchar(100) NOT NULL,
    error_message text NOT NULL,
    row_data_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_import_row_errors_job
    ON import_row_errors(tenant_id, import_job_id, row_number);

CREATE TABLE idempotency_keys (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    idempotency_key varchar(300) NOT NULL,
    http_method varchar(16) NOT NULL,
    request_path varchar(1000) NOT NULL,
    request_hash char(64) NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'PROCESSING'
        CHECK (state IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    response_status integer CHECK (response_status BETWEEN 100 AND 599),
    response_headers_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    response_body_json jsonb,
    resource_type varchar(100),
    resource_id uuid,
    locked_until timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expiry
    ON idempotency_keys(expires_at);

CREATE TABLE number_sequences (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    sequence_key varchar(200) NOT NULL,
    period_key varchar(100) NOT NULL,
    next_value bigint NOT NULL DEFAULT 1 CHECK (next_value > 0),
    padding smallint NOT NULL DEFAULT 3 CHECK (padding BETWEEN 1 AND 18),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, sequence_key, period_key)
);

CREATE TABLE background_jobs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    job_type varchar(100) NOT NULL,
    unique_key varchar(300),
    payload_json jsonb NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'LEASED', 'RETRY', 'COMPLETED', 'DEAD', 'CANCELLED')),
    priority integer NOT NULL DEFAULT 0,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL DEFAULT 10 CHECK (max_attempts > 0),
    scheduled_at timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz NOT NULL DEFAULT now(),
    leased_by varchar(200),
    leased_until timestamptz,
    last_error_code varchar(100),
    last_error_message text,
    result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE UNIQUE INDEX uq_background_job_unique_key
    ON background_jobs(tenant_id, job_type, unique_key)
    WHERE unique_key IS NOT NULL AND status <> 'CANCELLED';

CREATE INDEX idx_background_jobs_claim
    ON background_jobs(status, available_at, priority DESC, created_at)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_background_jobs_lease_expiry
    ON background_jobs(leased_until)
    WHERE status = 'LEASED';

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    event_version integer NOT NULL DEFAULT 1 CHECK (event_version > 0),
    payload_json jsonb NOT NULL,
    headers_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'RETRY', 'DEAD')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    locked_by varchar(200),
    locked_until timestamptz,
    last_error text,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX idx_outbox_events_claim
    ON outbox_events(status, available_at, occurred_at)
    WHERE status IN ('PENDING', 'RETRY');

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    actor_type varchar(24) NOT NULL
        CHECK (actor_type IN ('USER', 'CUSTOMER', 'SERVICE_ACCOUNT', 'SYSTEM')),
    actor_id uuid,
    actor_display varchar(300),
    action varchar(160) NOT NULL,
    object_type varchar(100) NOT NULL,
    object_id uuid,
    correlation_id varchar(160),
    request_id varchar(160),
    before_json jsonb,
    after_json jsonb,
    metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ip_address inet,
    user_agent varchar(1000),
    previous_hash char(64),
    event_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_object
    ON audit_logs(tenant_id, object_type, object_id, created_at DESC);

CREATE INDEX idx_audit_logs_actor
    ON audit_logs(tenant_id, actor_id, created_at DESC);

CREATE OR REPLACE FUNCTION deny_update_or_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE OR REPLACE FUNCTION protect_published_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('PUBLISHED', 'RETIRED') THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'published version in % cannot be deleted', TG_TABLE_NAME
                USING ERRCODE = '55000';
        END IF;

        IF (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') THEN
            RAISE EXCEPTION 'published version in % is immutable', TG_TABLE_NAME
                USING ERRCODE = '55000';
        END IF;

        IF OLD.status = 'RETIRED' AND NEW.status <> 'RETIRED' THEN
            RAISE EXCEPTION 'retired version in % cannot be reactivated', TG_TABLE_NAME
                USING ERRCODE = '55000';
        END IF;

        IF OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'RETIRED') THEN
            RAISE EXCEPTION 'published version in % can only be retired', TG_TABLE_NAME
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_pricing_rule_versions_immutable
BEFORE UPDATE OR DELETE ON pricing_rule_versions
FOR EACH ROW EXECUTE FUNCTION protect_published_version();

CREATE TRIGGER trg_template_versions_immutable
BEFORE UPDATE OR DELETE ON invoice_template_versions
FOR EACH ROW EXECUTE FUNCTION protect_published_version();

CREATE TRIGGER trg_workflow_versions_immutable
BEFORE UPDATE OR DELETE ON approval_workflow_versions
FOR EACH ROW EXECUTE FUNCTION protect_published_version();

CREATE OR REPLACE FUNCTION protect_formal_invoice()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    allowed_columns text[] := ARRAY[
        'document_status', 'send_status', 'payment_status', 'updated_at', 'version',
        'confirmed_at', 'sent_at', 'voided_at', 'paid_at'
    ];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'formal invoice cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF (to_jsonb(NEW) - allowed_columns) IS DISTINCT FROM (to_jsonb(OLD) - allowed_columns) THEN
        RAISE EXCEPTION 'frozen invoice fields cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.document_status <> OLD.document_status AND NOT (
        (OLD.document_status = 'FINALIZING' AND NEW.document_status IN ('CONFIRMED', 'VOIDED'))
        OR (OLD.document_status = 'CONFIRMED' AND NEW.document_status IN ('SENT', 'VOIDED'))
        OR (OLD.document_status = 'SENT' AND NEW.document_status IN ('VOIDED', 'REPLACED'))
        OR (OLD.document_status = 'VOIDED' AND NEW.document_status = 'REPLACED')
    ) THEN
        RAISE EXCEPTION 'invalid invoice document status transition: % -> %',
            OLD.document_status, NEW.document_status
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_invoices_protect_frozen_fields
BEFORE UPDATE OR DELETE ON invoices
FOR EACH ROW EXECUTE FUNCTION protect_formal_invoice();

CREATE TRIGGER trg_invoice_items_append_only
BEFORE UPDATE OR DELETE ON invoice_items
FOR EACH ROW EXECUTE FUNCTION deny_update_or_delete();

CREATE TRIGGER trg_invoice_adjustments_append_only
BEFORE UPDATE OR DELETE ON invoice_adjustments
FOR EACH ROW EXECUTE FUNCTION deny_update_or_delete();

CREATE TRIGGER trg_invoice_files_append_only
BEFORE UPDATE OR DELETE ON invoice_files
FOR EACH ROW EXECUTE FUNCTION deny_update_or_delete();

CREATE TRIGGER trg_invoice_relations_append_only
BEFORE UPDATE OR DELETE ON invoice_relations
FOR EACH ROW EXECUTE FUNCTION deny_update_or_delete();

CREATE TRIGGER trg_audit_logs_append_only
BEFORE UPDATE OR DELETE ON audit_logs
FOR EACH ROW EXECUTE FUNCTION deny_update_or_delete();

CREATE OR REPLACE FUNCTION protect_referenced_usage_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.snapshot_kind = 'FINAL' THEN
        RAISE EXCEPTION 'final usage snapshot is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM invoice_items
        WHERE usage_snapshot_id = OLD.id
    ) THEN
        RAISE EXCEPTION 'usage snapshot referenced by a formal invoice is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_usage_snapshots_protect_formal_reference
BEFORE UPDATE OR DELETE ON usage_snapshots
FOR EACH ROW EXECUTE FUNCTION protect_referenced_usage_snapshot();

