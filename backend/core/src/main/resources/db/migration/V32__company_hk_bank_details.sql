ALTER TABLE public.companies
    ADD COLUMN swift_code varchar(16),
    ADD COLUMN bank_code varchar(32),
    ADD COLUMN bank_address text;
