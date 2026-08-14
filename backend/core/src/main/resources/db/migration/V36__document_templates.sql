CREATE TABLE public.document_templates (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    template_code varchar(100) NOT NULL,
    template_name varchar(240) NOT NULL,
    template_type varchar(24) NOT NULL
        CHECK (template_type IN ('CONTRACT_DOCX', 'INVOICE_XLSX')),
    file_id uuid,
    description text,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, template_code)
);

ALTER TABLE public.document_templates
    ADD CONSTRAINT uq_tenant_document_templates_id UNIQUE (tenant_id, id),
    ADD CONSTRAINT fk_tenant_document_templates_file
        FOREIGN KEY (tenant_id, file_id)
        REFERENCES public.files (tenant_id, id)
        MATCH SIMPLE ON UPDATE NO ACTION ON DELETE SET NULL NOT VALID;
