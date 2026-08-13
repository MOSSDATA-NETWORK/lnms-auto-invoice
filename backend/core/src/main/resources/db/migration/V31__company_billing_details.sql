ALTER TABLE public.companies
    ADD COLUMN phone varchar(64),
    ADD COLUMN bank_name varchar(240),
    ADD COLUMN bank_account varchar(128),
    ADD COLUMN invoice_type varchar(24) NOT NULL DEFAULT 'GENERAL'
        CHECK (invoice_type IN ('GENERAL', 'SPECIAL'));
