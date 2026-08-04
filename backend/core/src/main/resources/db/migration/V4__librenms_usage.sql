CREATE TABLE librenms_instances (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    instance_code varchar(100) NOT NULL,
    instance_name varchar(240) NOT NULL,
    base_url varchar(1000) NOT NULL,
    api_token_ciphertext text NOT NULL,
    api_version varchar(32),
    timezone varchar(64) NOT NULL,
    connect_timeout_ms integer NOT NULL DEFAULT 5000 CHECK (connect_timeout_ms > 0),
    read_timeout_ms integer NOT NULL DEFAULT 30000 CHECK (read_timeout_ms > 0),
    max_concurrency integer NOT NULL DEFAULT 4 CHECK (max_concurrency BETWEEN 1 AND 100),
    tls_verify boolean NOT NULL DEFAULT true,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'ERROR')),
    last_success_at timestamptz,
    last_failure_at timestamptz,
    consecutive_failures integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, instance_code)
);

CREATE TABLE librenms_bill_mappings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    librenms_instance_id uuid NOT NULL REFERENCES librenms_instances(id),
    librenms_bill_id bigint NOT NULL,
    observed_bill_name varchar(500),
    observed_bill_ref varchar(500),
    observed_bill_custid varchar(500),
    customer_id uuid NOT NULL REFERENCES customers(id),
    company_id uuid NOT NULL REFERENCES companies(id),
    service_id uuid NOT NULL REFERENCES services(id),
    contract_item_id uuid NOT NULL REFERENCES contract_items(id),
    billing_direction varchar(24) NOT NULL DEFAULT 'MAX'
        CHECK (billing_direction IN ('MAX', 'INBOUND', 'OUTBOUND', 'AGGREGATE', 'LIBRENMS_FINAL')),
    source_unit varchar(48),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    discovery_status varchar(24) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (discovery_status IN ('DISCOVERED', 'CONFIRMED', 'REJECTED')),
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ERROR')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    UNIQUE (tenant_id, librenms_instance_id, librenms_bill_id, effective_from)
);

CREATE UNIQUE INDEX uq_active_mapping_contract_item
    ON librenms_bill_mappings(tenant_id, contract_item_id)
    WHERE status = 'ACTIVE' AND effective_to IS NULL;

CREATE TABLE usage_sync_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    librenms_instance_id uuid NOT NULL REFERENCES librenms_instances(id),
    mapping_id uuid REFERENCES librenms_bill_mappings(id),
    job_id uuid,
    sync_type varchar(32) NOT NULL
        CHECK (sync_type IN ('DISCOVER', 'CURRENT', 'PERIOD_HISTORY', 'GRAPH', 'VERIFY')),
    period_start timestamptz,
    period_end timestamptz,
    status varchar(24) NOT NULL
        CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED')),
    request_count integer NOT NULL DEFAULT 0,
    response_hash char(64),
    duration_ms bigint,
    summary_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(100),
    error_message text,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE usage_current (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    mapping_id uuid NOT NULL REFERENCES librenms_bill_mappings(id),
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    rate_95th_in_bps bigint,
    rate_95th_out_bps bigint,
    rate_95th_bps bigint,
    rate_average_bps bigint,
    rate_peak_bps bigint,
    traffic_in_bytes numeric(30,0),
    traffic_out_bytes numeric(30,0),
    traffic_total_bytes numeric(30,0),
    sample_coverage numeric(12,8),
    source_updated_at timestamptz,
    response_hash char(64),
    anomaly_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end > period_start),
    UNIQUE (tenant_id, mapping_id, period_start, period_end)
);

CREATE TABLE usage_snapshots (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    contract_item_id uuid NOT NULL REFERENCES contract_items(id),
    mapping_id uuid REFERENCES librenms_bill_mappings(id),
    librenms_instance_id uuid REFERENCES librenms_instances(id),
    librenms_bill_id bigint,
    bill_hist_id bigint,
    snapshot_kind varchar(24) NOT NULL
        CHECK (snapshot_kind IN ('PREVIEW', 'FINAL', 'MANUAL_CORRECTION')),
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    rate_95th_in_bps bigint,
    rate_95th_out_bps bigint,
    rate_95th_bps bigint,
    rate_average_bps bigint,
    rate_peak_bps bigint,
    traffic_in_bytes numeric(30,0),
    traffic_out_bytes numeric(30,0),
    traffic_total_bytes numeric(30,0),
    billing_direction varchar(24),
    raw_usage numeric(30,12),
    converted_usage numeric(30,12),
    rounded_usage numeric(30,12),
    billing_usage numeric(30,12),
    unit varchar(48),
    sample_coverage numeric(12,8),
    source_timezone varchar(64),
    adapter_version varchar(100),
    data_hash char(64) NOT NULL,
    anomaly_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    invalidated_at timestamptz,
    invalidation_reason text,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end > period_start),
    UNIQUE (tenant_id, contract_item_id, period_start, period_end, snapshot_kind, data_hash)
);

CREATE TABLE usage_snapshot_files (
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    usage_snapshot_id uuid NOT NULL REFERENCES usage_snapshots(id),
    file_id uuid NOT NULL REFERENCES files(id),
    file_role varchar(32) NOT NULL
        CHECK (file_role IN ('RAW_RESPONSE', 'GRAPH_BITS', 'GRAPH_DAY', 'GRAPH_MONTH', 'GRAPHDATA', 'OTHER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, usage_snapshot_id, file_id, file_role)
);

