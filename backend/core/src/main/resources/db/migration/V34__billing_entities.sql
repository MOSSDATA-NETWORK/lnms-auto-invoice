CREATE TABLE public.billing_entities (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    entity_code varchar(100) NOT NULL,
    entity_name varchar(300) NOT NULL,
    entity_name_en varchar(300),
    country_region varchar(100),
    address text,
    phone varchar(64),
    tax_number varchar(160),
    br_number varchar(32),
    invoice_title varchar(300),
    bank_name varchar(240),
    bank_code varchar(32),
    swift_code varchar(16),
    bank_address text,
    bank_account varchar(128),
    default_currency char(3) REFERENCES public.currencies(code),
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, entity_code)
);

ALTER TABLE public.billing_entities
    ADD CONSTRAINT uq_tenant_billing_entities_id UNIQUE (tenant_id, id);

ALTER TABLE public.invoice_profiles
    ADD COLUMN billing_entity_id uuid,
    ADD CONSTRAINT fk_tenant_invoice_profiles_billing_entity
        FOREIGN KEY (tenant_id, billing_entity_id)
        REFERENCES public.billing_entities (tenant_id, id)
        MATCH SIMPLE ON UPDATE NO ACTION ON DELETE RESTRICT NOT VALID;
