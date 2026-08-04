ALTER TABLE payment_allocations
    DROP CONSTRAINT ck_payment_allocations_reversal_state;

ALTER TABLE payment_allocations
    ADD CONSTRAINT ck_payment_allocations_reversal_state
        CHECK (
            (status = 'ACTIVE' AND reversed_by IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL)
            OR (status = 'REVERSED' AND reversed_by IS NOT NULL AND reversed_at IS NOT NULL
                AND reversal_reason IS NOT NULL AND btrim(reversal_reason) <> '')
        ) NOT VALID;

ALTER TABLE payment_allocations
    VALIDATE CONSTRAINT ck_payment_allocations_reversal_state;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_status_derived
        CHECK (status IN (
            'CONFIRMED', 'PARTIALLY_ALLOCATED', 'ALLOCATED',
            'PARTIALLY_REFUNDED', 'REFUNDED'
        )) NOT VALID;

ALTER TABLE payments
    VALIDATE CONSTRAINT ck_payments_status_derived;

CREATE OR REPLACE FUNCTION protect_payment_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    allowed_columns text[] := ARRAY['status', 'updated_at', 'version'];
    allocated_minor numeric;
    refunded_minor numeric;
    expected_status text;
BEGIN
    IF (to_jsonb(NEW) - allowed_columns) IS DISTINCT FROM (to_jsonb(OLD) - allowed_columns) THEN
        RAISE EXCEPTION 'payment financial and identity fields are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payments_history_immutable';
    END IF;
    IF pg_trigger_depth() <= 1 THEN
        RAISE EXCEPTION 'payment status is derived from active allocations and confirmed refunds'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payments_status_derived';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'payment status updates require a monotonic version and timestamp'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payments_status_update';
    END IF;

    SELECT COALESCE(sum(allocation.amount_minor), 0)
    INTO allocated_minor
    FROM payment_allocations allocation
    WHERE allocation.tenant_id = NEW.tenant_id
      AND allocation.payment_id = NEW.id
      AND allocation.status = 'ACTIVE';

    SELECT COALESCE(sum(refund.amount_minor), 0)
    INTO refunded_minor
    FROM payment_refunds refund
    WHERE refund.tenant_id = NEW.tenant_id
      AND refund.payment_id = NEW.id
      AND refund.status = 'CONFIRMED';

    expected_status := CASE
        WHEN refunded_minor = NEW.amount_minor THEN 'REFUNDED'
        WHEN refunded_minor > 0 THEN 'PARTIALLY_REFUNDED'
        WHEN allocated_minor = NEW.amount_minor THEN 'ALLOCATED'
        WHEN allocated_minor > 0 THEN 'PARTIALLY_ALLOCATED'
        ELSE 'CONFIRMED'
    END;

    IF NEW.status <> expected_status THEN
        RAISE EXCEPTION 'payment status % does not match derived status %', NEW.status, expected_status
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payments_status_derived';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION protect_payment_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'CONFIRMED' OR NEW.version <> 0 THEN
        RAISE EXCEPTION 'a new payment must start at CONFIRMED with version zero'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payments_initial_state';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payments_protect_initial_state
BEFORE INSERT ON payments
FOR EACH ROW EXECUTE FUNCTION protect_payment_insert();

CREATE OR REPLACE FUNCTION refresh_payment_status_from_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_tenant_id uuid;
    target_payment_id uuid;
    payment_amount bigint;
    allocated_minor numeric;
    refunded_minor numeric;
    derived_status text;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;

    target_tenant_id := NEW.tenant_id;
    target_payment_id := NEW.payment_id;

    SELECT payment.amount_minor
    INTO payment_amount
    FROM payments payment
    WHERE payment.tenant_id = target_tenant_id
      AND payment.id = target_payment_id
    FOR UPDATE;

    IF payment_amount IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT COALESCE(sum(allocation.amount_minor), 0)
    INTO allocated_minor
    FROM payment_allocations allocation
    WHERE allocation.tenant_id = target_tenant_id
      AND allocation.payment_id = target_payment_id
      AND allocation.status = 'ACTIVE';

    SELECT COALESCE(sum(refund.amount_minor), 0)
    INTO refunded_minor
    FROM payment_refunds refund
    WHERE refund.tenant_id = target_tenant_id
      AND refund.payment_id = target_payment_id
      AND refund.status = 'CONFIRMED';

    derived_status := CASE
        WHEN refunded_minor = payment_amount THEN 'REFUNDED'
        WHEN refunded_minor > 0 THEN 'PARTIALLY_REFUNDED'
        WHEN allocated_minor = payment_amount THEN 'ALLOCATED'
        WHEN allocated_minor > 0 THEN 'PARTIALLY_ALLOCATED'
        ELSE 'CONFIRMED'
    END;

    UPDATE payments
    SET status = derived_status,
        updated_at = now(),
        version = version + 1
    WHERE tenant_id = target_tenant_id
      AND id = target_payment_id;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_allocations_refresh_payment_status
AFTER INSERT OR UPDATE ON payment_allocations
FOR EACH ROW EXECUTE FUNCTION refresh_payment_status_from_history();

CREATE TRIGGER trg_payment_refunds_refresh_payment_status
AFTER INSERT OR UPDATE ON payment_refunds
FOR EACH ROW EXECUTE FUNCTION refresh_payment_status_from_history();

CREATE OR REPLACE FUNCTION protect_payment_allocation_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    reversal_columns text[] := ARRAY[
        'status', 'reversed_by', 'reversed_at', 'reversal_reason'
    ];
BEGIN
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
END;
$$;
