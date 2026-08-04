CREATE TABLE customers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    customer_no varchar(64) NOT NULL,
    customer_name varchar(240) NOT NULL,
    customer_type varchar(24) NOT NULL DEFAULT 'ENTERPRISE'
        CHECK (customer_type IN ('ENTERPRISE', 'INDIVIDUAL', 'RESELLER', 'INTERNAL')),
    owner_user_id uuid REFERENCES users(id),
    default_currency char(3) REFERENCES currencies(code),
    default_language varchar(16) NOT NULL DEFAULT 'zh-CN',
    default_billing_cycle varchar(32) NOT NULL DEFAULT 'MONTHLY',
    default_payment_terms_days integer NOT NULL DEFAULT 7 CHECK (default_payment_terms_days >= 0),
    credit_limit_minor bigint,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('PROSPECT', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    tags_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, customer_no)
);

CREATE INDEX idx_customers_name ON customers(tenant_id, customer_name);

CREATE TABLE companies (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    customer_id uuid NOT NULL REFERENCES customers(id),
    company_code varchar(64) NOT NULL,
    company_name varchar(300) NOT NULL,
    company_name_en varchar(300),
    country_region varchar(100),
    address text,
    tax_number varchar(160),
    invoice_title varchar(300),
    invoice_profile_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    default_currency char(3) REFERENCES currencies(code),
    default_tax_rate numeric(12,8) CHECK (default_tax_rate BETWEEN 0 AND 1),
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, company_code)
);

CREATE INDEX idx_companies_customer ON companies(tenant_id, customer_id);

CREATE TABLE customer_contacts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    customer_id uuid NOT NULL REFERENCES customers(id),
    company_id uuid REFERENCES companies(id),
    contact_name varchar(160) NOT NULL,
    contact_type varchar(24) NOT NULL DEFAULT 'GENERAL'
        CHECK (contact_type IN ('GENERAL', 'BUSINESS', 'TECHNICAL', 'FINANCE', 'BILLING')),
    email varchar(320),
    phone varchar(64),
    telegram varchar(160),
    wecom varchar(160),
    language varchar(16),
    receives_invoice boolean NOT NULL DEFAULT false,
    receives_reminder boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    product_code varchar(64) NOT NULL,
    product_name varchar(200) NOT NULL,
    service_type varchar(48) NOT NULL,
    default_unit varchar(48),
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    attributes_schema jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, product_code)
);

CREATE TABLE service_groups (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    group_code varchar(64) NOT NULL,
    group_name varchar(200) NOT NULL,
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, group_code)
);

CREATE TABLE services (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    service_no varchar(80) NOT NULL,
    customer_id uuid NOT NULL REFERENCES customers(id),
    company_id uuid NOT NULL REFERENCES companies(id),
    product_id uuid REFERENCES products(id),
    service_group_id uuid REFERENCES service_groups(id),
    service_name varchar(300) NOT NULL,
    service_type varchar(48) NOT NULL,
    region varchar(120),
    datacenter varchar(160),
    line_name varchar(200),
    activated_on date,
    deactivated_on date,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'ENDED', 'CANCELLED')),
    attributes_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (deactivated_on IS NULL OR activated_on IS NULL OR deactivated_on >= activated_on),
    UNIQUE (tenant_id, service_no)
);

CREATE INDEX idx_services_customer ON services(tenant_id, customer_id, status);
CREATE INDEX idx_services_company ON services(tenant_id, company_id, status);

CREATE TABLE service_resources (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    service_id uuid NOT NULL REFERENCES services(id),
    resource_type varchar(32) NOT NULL,
    resource_ref varchar(300) NOT NULL,
    display_name varchar(300),
    attributes_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    effective_from timestamptz,
    effective_to timestamptz,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from),
    UNIQUE (tenant_id, service_id, resource_type, resource_ref)
);

CREATE TABLE contracts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    contract_no varchar(80) NOT NULL,
    customer_id uuid NOT NULL REFERENCES customers(id),
    company_id uuid NOT NULL REFERENCES companies(id),
    contract_name varchar(300) NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    auto_renew boolean NOT NULL DEFAULT false,
    billing_cycle varchar(32) NOT NULL DEFAULT 'MONTHLY',
    billing_day smallint CHECK (billing_day BETWEEN 1 AND 28),
    payment_terms_days integer NOT NULL DEFAULT 7 CHECK (payment_terms_days >= 0),
    currency_code char(3) NOT NULL REFERENCES currencies(code),
    tax_rate numeric(12,8) CHECK (tax_rate BETWEEN 0 AND 1),
    tax_inclusive boolean NOT NULL DEFAULT false,
    status varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED', 'EXPIRED', 'TERMINATED', 'VOIDED')),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    UNIQUE (tenant_id, contract_no)
);

CREATE TABLE contract_files (
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    contract_id uuid NOT NULL REFERENCES contracts(id),
    file_id uuid NOT NULL REFERENCES files(id),
    file_type varchar(32) NOT NULL DEFAULT 'ATTACHMENT',
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, contract_id, file_id)
);

CREATE TABLE pricing_rules (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    rule_code varchar(80) NOT NULL,
    rule_name varchar(240) NOT NULL,
    description text,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, rule_code)
);

CREATE TABLE pricing_rule_versions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    pricing_rule_id uuid NOT NULL REFERENCES pricing_rules(id),
    version_no integer NOT NULL CHECK (version_no > 0),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    billing_type varchar(48) NOT NULL,
    currency_code char(3) NOT NULL REFERENCES currencies(code),
    unit varchar(48),
    unit_price numeric(30,12),
    base_fee numeric(30,12),
    committed_quantity numeric(30,12),
    overage_unit_price numeric(30,12),
    minimum_charge numeric(30,12),
    maximum_charge numeric(30,12),
    discount_rate numeric(12,8) CHECK (discount_rate BETWEEN 0 AND 1),
    tax_rate numeric(12,8) CHECK (tax_rate BETWEEN 0 AND 1),
    rounding_mode varchar(32) NOT NULL DEFAULT 'NONE',
    rounding_scale integer,
    config_schema_version integer NOT NULL DEFAULT 1,
    config_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    change_note text,
    created_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (maximum_charge IS NULL OR minimum_charge IS NULL OR maximum_charge >= minimum_charge),
    UNIQUE (pricing_rule_id, version_no)
);

CREATE INDEX idx_pricing_versions_effective
    ON pricing_rule_versions(pricing_rule_id, effective_from, effective_to)
    WHERE status = 'PUBLISHED';

CREATE TABLE pricing_tiers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    pricing_rule_version_id uuid NOT NULL REFERENCES pricing_rule_versions(id) ON DELETE CASCADE,
    tier_no integer NOT NULL CHECK (tier_no > 0),
    lower_bound numeric(30,12) NOT NULL,
    upper_bound numeric(30,12),
    unit_price numeric(30,12) NOT NULL,
    pricing_mode varchar(24) NOT NULL CHECK (pricing_mode IN ('GRADUATED', 'VOLUME')),
    CHECK (upper_bound IS NULL OR upper_bound > lower_bound),
    UNIQUE (pricing_rule_version_id, tier_no)
);

CREATE TABLE contract_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    contract_item_no varchar(100) NOT NULL,
    contract_id uuid NOT NULL REFERENCES contracts(id),
    service_id uuid NOT NULL REFERENCES services(id),
    pricing_rule_id uuid NOT NULL REFERENCES pricing_rules(id),
    item_name varchar(300) NOT NULL,
    billing_type varchar(48) NOT NULL,
    billing_cycle varchar(32) NOT NULL DEFAULT 'MONTHLY',
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    default_quantity numeric(30,12),
    unit varchar(48),
    tax_category varchar(64),
    auto_bill boolean NOT NULL DEFAULT true,
    visible_on_invoice boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'ENDED', 'CANCELLED')),
    attributes_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    UNIQUE (tenant_id, contract_item_no)
);

CREATE INDEX idx_contract_items_contract ON contract_items(tenant_id, contract_id, status);
CREATE INDEX idx_contract_items_service ON contract_items(tenant_id, service_id, status);

