-- A payment child insert already holds KEY SHARE on its parent through the
-- foreign key. Escalating that lock to FOR UPDATE can deadlock when two child
-- transactions reach their AFTER triggers concurrently. FOR NO KEY UPDATE is
-- compatible with those foreign-key locks while still serializing the balance
-- check and the derived-status refresh against each other.

CREATE OR REPLACE FUNCTION public.enforce_payment_balance_conservation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    target_tenant_id uuid;
    target_payment_id uuid;
    payment_amount bigint;
    allocated_amount numeric;
    refunded_amount numeric;
BEGIN
    IF TG_TABLE_NAME = 'payments' THEN
        target_tenant_id := NEW.tenant_id;
        target_payment_id := NEW.id;
    ELSE
        target_tenant_id := NEW.tenant_id;
        target_payment_id := NEW.payment_id;
    END IF;

    SELECT payment.amount_minor
    INTO payment_amount
    FROM payments payment
    WHERE payment.tenant_id = target_tenant_id
      AND payment.id = target_payment_id
    FOR NO KEY UPDATE;

    IF payment_amount IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT COALESCE(sum(allocation.amount_minor), 0)
    INTO allocated_amount
    FROM payment_allocations allocation
    WHERE allocation.tenant_id = target_tenant_id
      AND allocation.payment_id = target_payment_id
      AND allocation.status = 'ACTIVE';

    SELECT COALESCE(sum(refund.amount_minor), 0)
    INTO refunded_amount
    FROM payment_refunds refund
    WHERE refund.tenant_id = target_tenant_id
      AND refund.payment_id = target_payment_id
      AND refund.status = 'CONFIRMED';

    IF allocated_amount + refunded_amount > payment_amount THEN
        RAISE EXCEPTION
            'Payment % balance is not conserved: amount %, allocated %, refunded %',
            target_payment_id, payment_amount, allocated_amount, refunded_amount
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_balance_conserved';
    END IF;

    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION public.refresh_payment_status_from_history()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
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
    FOR NO KEY UPDATE;

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
        updated_at = clock_timestamp(),
        version = version + 1
    WHERE tenant_id = target_tenant_id
      AND id = target_payment_id;

    RETURN NEW;
END
$$;
