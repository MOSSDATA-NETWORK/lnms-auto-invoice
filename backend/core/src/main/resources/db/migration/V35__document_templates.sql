ALTER TABLE public.contracts
    ADD COLUMN template_file_id uuid,
    ADD CONSTRAINT fk_tenant_contracts_template_file
        FOREIGN KEY (tenant_id, template_file_id)
        REFERENCES public.files (tenant_id, id)
        MATCH SIMPLE ON UPDATE NO ACTION ON DELETE SET NULL NOT VALID;

ALTER TABLE public.invoice_profiles
    ADD COLUMN excel_template_file_id uuid,
    ADD CONSTRAINT fk_tenant_invoice_profiles_excel_template
        FOREIGN KEY (tenant_id, excel_template_file_id)
        REFERENCES public.files (tenant_id, id)
        MATCH SIMPLE ON UPDATE NO ACTION ON DELETE SET NULL NOT VALID;
