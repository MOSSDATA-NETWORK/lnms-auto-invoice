ALTER TABLE public.invoice_profiles
    ADD COLUMN document_template_id uuid,
    ADD CONSTRAINT fk_tenant_invoice_profiles_document_template
        FOREIGN KEY (tenant_id, document_template_id)
        REFERENCES public.document_templates (tenant_id, id)
        MATCH SIMPLE ON UPDATE NO ACTION ON DELETE SET NULL NOT VALID;
