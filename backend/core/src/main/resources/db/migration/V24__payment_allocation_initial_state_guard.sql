-- Allocation history must be created as ACTIVE. A row born REVERSED would
-- bypass the only supported ACTIVE -> REVERSED transition and create an
-- immutable fabricated reversal record.

CREATE OR REPLACE FUNCTION public.protect_payment_allocation_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    reversal_columns text[] := ARRAY[
        'status', 'reversed_by', 'reversed_at', 'reversal_reason'
    ];
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE'
                OR NEW.reversed_by IS NOT NULL
                OR NEW.reversed_at IS NOT NULL
                OR NEW.reversal_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a new payment allocation must start ACTIVE without reversal metadata'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_initial_state';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'ACTIVE' OR NEW.status <> 'REVERSED' THEN
        RAISE EXCEPTION 'payment allocation only supports ACTIVE to REVERSED'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_status_transition';
    END IF;
    IF (to_jsonb(NEW) - reversal_columns) IS DISTINCT FROM (to_jsonb(OLD) - reversal_columns) THEN
        RAISE EXCEPTION 'payment allocation financial and identity fields are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_history_immutable';
    END IF;
    IF NEW.reversed_by IS NULL OR NEW.reversed_at IS NULL
            OR NEW.reversal_reason IS NULL OR btrim(NEW.reversal_reason) = '' THEN
        RAISE EXCEPTION 'reversed payment allocation requires actor, time, and reason'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_reversal_state';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_payment_allocations_protect_initial_state
BEFORE INSERT ON public.payment_allocations
FOR EACH ROW EXECUTE FUNCTION public.protect_payment_allocation_update();
