CREATE TABLE invoice_templates (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    template_code varchar(100) NOT NULL,
    template_name varchar(240) NOT NULL,
    template_type varchar(32) NOT NULL DEFAULT 'HTML',
    default_language varchar(16) NOT NULL DEFAULT 'zh-CN',
    status varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    current_version_id uuid,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, template_code)
);

CREATE TABLE invoice_template_versions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    template_id uuid NOT NULL REFERENCES invoice_templates(id),
    version_no integer NOT NULL CHECK (version_no > 0),
    html_content text NOT NULL,
    css_content text NOT NULL DEFAULT '',
    schema_json jsonb NOT NULL,
    field_config_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    list_config_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    content_sha256 char(64) NOT NULL,
    change_note text,
    status varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (template_id, version_no)
);

ALTER TABLE invoice_templates
    ADD CONSTRAINT fk_template_current_version
    FOREIGN KEY (current_version_id) REFERENCES invoice_template_versions(id);

CREATE TABLE invoice_template_assets (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    template_version_id uuid NOT NULL REFERENCES invoice_template_versions(id) ON DELETE CASCADE,
    asset_key varchar(200) NOT NULL,
    file_id uuid NOT NULL REFERENCES files(id),
    usage_type varchar(32) NOT NULL
        CHECK (usage_type IN ('LOGO', 'IMAGE', 'FONT', 'STAMP', 'OTHER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (template_version_id, asset_key)
);

CREATE TABLE template_bindings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    template_id uuid NOT NULL REFERENCES invoice_templates(id),
    scope_type varchar(32) NOT NULL
        CHECK (scope_type IN ('SYSTEM', 'CUSTOMER', 'COMPANY', 'SERVICE_GROUP', 'SERVICE', 'CONTRACT', 'CONTRACT_ITEM', 'INVOICE_PROFILE')),
    scope_id uuid,
    currency_code char(3) REFERENCES currencies(code),
    language varchar(16),
    priority integer NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (tenant_id, scope_type, scope_id, currency_code, language, priority)
);

CREATE TABLE approval_workflows (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    workflow_code varchar(100) NOT NULL,
    workflow_name varchar(240) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    current_version_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, workflow_code)
);

CREATE TABLE approval_workflow_versions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    workflow_id uuid NOT NULL REFERENCES approval_workflows(id),
    version_no integer NOT NULL CHECK (version_no > 0),
    conditions_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (workflow_id, version_no)
);

ALTER TABLE approval_workflows
    ADD CONSTRAINT fk_workflow_current_version
    FOREIGN KEY (current_version_id) REFERENCES approval_workflow_versions(id);

CREATE TABLE approval_steps (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    workflow_version_id uuid NOT NULL REFERENCES approval_workflow_versions(id) ON DELETE CASCADE,
    step_no integer NOT NULL CHECK (step_no > 0),
    step_code varchar(100) NOT NULL,
    step_name varchar(200) NOT NULL,
    permission_code varchar(160) REFERENCES permissions(permission_code),
    conditions_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workflow_version_id, step_no),
    UNIQUE (workflow_version_id, step_code)
);

CREATE TABLE invoice_profiles (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    profile_code varchar(100) NOT NULL,
    profile_name varchar(240) NOT NULL,
    customer_id uuid NOT NULL REFERENCES customers(id),
    company_id uuid NOT NULL REFERENCES companies(id),
    template_id uuid REFERENCES invoice_templates(id),
    approval_workflow_id uuid REFERENCES approval_workflows(id),
    language varchar(16) NOT NULL DEFAULT 'zh-CN',
    currency_code char(3) NOT NULL REFERENCES currencies(code),
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
    billing_cycle varchar(32) NOT NULL DEFAULT 'MONTHLY',
    billing_day smallint CHECK (billing_day BETWEEN 1 AND 28),
    payment_terms_days integer NOT NULL DEFAULT 7 CHECK (payment_terms_days >= 0),
    tax_calculation_mode varchar(24) NOT NULL DEFAULT 'PER_LINE'
        CHECK (tax_calculation_mode IN ('PER_LINE', 'INVOICE_TOTAL')),
    invoice_number_rule varchar(300) NOT NULL,
    payment_account_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    recipients_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    auto_generate boolean NOT NULL DEFAULT true,
    auto_submit_review boolean NOT NULL DEFAULT false,
    auto_send boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, profile_code)
);

CREATE TABLE invoice_profile_assignments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    invoice_profile_id uuid NOT NULL REFERENCES invoice_profiles(id) ON DELETE CASCADE,
    contract_item_id uuid NOT NULL REFERENCES contract_items(id),
    assignment_mode varchar(32) NOT NULL DEFAULT 'CHARGE'
        CHECK (assignment_mode IN ('CHARGE', 'ALLOCATE_PERCENT', 'ALLOCATE_FIXED', 'DISPLAY_ONLY')),
    allocation_value numeric(30,12),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    sort_order integer NOT NULL DEFAULT 0,
    reason text,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (
        (assignment_mode IN ('CHARGE', 'DISPLAY_ONLY') AND allocation_value IS NULL)
        OR (assignment_mode = 'ALLOCATE_PERCENT' AND allocation_value > 0 AND allocation_value <= 100)
        OR (assignment_mode = 'ALLOCATE_FIXED' AND allocation_value >= 0)
    )
);

CREATE UNIQUE INDEX uq_active_charge_assignment
    ON invoice_profile_assignments(tenant_id, contract_item_id)
    WHERE assignment_mode = 'CHARGE' AND status = 'ACTIVE' AND effective_to IS NULL;

