DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'auto_invoice_app') THEN
        GRANT USAGE ON SCHEMA public TO auto_invoice_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO auto_invoice_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO auto_invoice_app;
        GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO auto_invoice_app;

        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO auto_invoice_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT USAGE, SELECT ON SEQUENCES TO auto_invoice_app;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public
            GRANT EXECUTE ON FUNCTIONS TO auto_invoice_app;

        -- Immutable and financial history remains protected even if an
        -- application defect bypasses the row-level trigger predicates.
        REVOKE UPDATE, DELETE ON audit_logs, approval_actions,
            invoice_items, invoice_adjustments, invoice_files, invoice_relations
            FROM auto_invoice_app;
        REVOKE DELETE ON approval_instances, invoices, payments,
            payment_allocations, payment_refunds
            FROM auto_invoice_app;

        -- Runtime processes never own or mutate migration history.
        REVOKE ALL ON flyway_schema_history FROM auto_invoice_app;
    END IF;
END
$$;
