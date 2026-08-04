-- A formal invoice must enter the lifecycle through FINALIZING. Allowing a row
-- to be born CONFIRMED, SENT, VOIDED, paid, or already versioned would bypass
-- the transition checks that protect the frozen invoice history.

CREATE OR REPLACE FUNCTION public.lock_approval_history_for_formal_invoice()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF NEW.document_status <> 'FINALIZING'
            OR NEW.send_status <> 'NOT_QUEUED'
            OR NEW.payment_status <> 'UNPAID'
            OR NEW.version <> 0
            OR NEW.confirmed_at IS NOT NULL
            OR NEW.sent_at IS NOT NULL
            OR NEW.voided_at IS NOT NULL
            OR NEW.paid_at IS NOT NULL THEN
        RAISE EXCEPTION 'a formal invoice must start in the initial lifecycle state'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_initial_state';
    END IF;

    PERFORM 1
    FROM public.approval_instances approval
    WHERE approval.tenant_id = NEW.tenant_id
      AND approval.id = NEW.approval_instance_id
    FOR UPDATE;
    RETURN NEW;
END
$$;
