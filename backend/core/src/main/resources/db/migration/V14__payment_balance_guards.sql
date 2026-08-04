DO $$
DECLARE
    invalid_payment_id uuid;
BEGIN
    SELECT payment.id
    INTO invalid_payment_id
    FROM payments payment
    WHERE COALESCE((
              SELECT sum(allocation.amount_minor)
              FROM payment_allocations allocation
              WHERE allocation.tenant_id = payment.tenant_id
                AND allocation.payment_id = payment.id
                AND allocation.status = 'ACTIVE'
          ), 0)
        + COALESCE((
              SELECT sum(refund.amount_minor)
              FROM payment_refunds refund
              WHERE refund.tenant_id = payment.tenant_id
                AND refund.payment_id = payment.id
                AND refund.status = 'CONFIRMED'
          ), 0) > payment.amount_minor
    LIMIT 1;

    IF invalid_payment_id IS NOT NULL THEN
        RAISE EXCEPTION 'Payment % has allocations plus confirmed refunds above its amount', invalid_payment_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_balance_conserved';
    END IF;
END
$$;

WITH balances AS (
    SELECT payment.id,
           payment.tenant_id,
           payment.amount_minor,
           COALESCE((
               SELECT sum(allocation.amount_minor)
               FROM payment_allocations allocation
               WHERE allocation.tenant_id = payment.tenant_id
                 AND allocation.payment_id = payment.id
                 AND allocation.status = 'ACTIVE'
           ), 0) AS allocated_minor,
           COALESCE((
               SELECT sum(refund.amount_minor)
               FROM payment_refunds refund
               WHERE refund.tenant_id = payment.tenant_id
                 AND refund.payment_id = payment.id
                 AND refund.status = 'CONFIRMED'
           ), 0) AS refunded_minor
    FROM payments payment
    WHERE payment.status IN (
        'CONFIRMED', 'PARTIALLY_ALLOCATED', 'ALLOCATED', 'PARTIALLY_REFUNDED', 'REFUNDED'
    )
), derived AS (
    SELECT id,
           tenant_id,
           CASE
               WHEN refunded_minor = amount_minor THEN 'REFUNDED'
               WHEN refunded_minor > 0 THEN 'PARTIALLY_REFUNDED'
               WHEN allocated_minor = amount_minor THEN 'ALLOCATED'
               WHEN allocated_minor > 0 THEN 'PARTIALLY_ALLOCATED'
               ELSE 'CONFIRMED'
           END AS status
    FROM balances
)
UPDATE payments payment
SET status = derived.status,
    updated_at = now(),
    version = payment.version + 1
FROM derived
WHERE payment.tenant_id = derived.tenant_id
  AND payment.id = derived.id
  AND payment.status IS DISTINCT FROM derived.status;

CREATE OR REPLACE FUNCTION enforce_payment_balance_conservation()
RETURNS trigger
LANGUAGE plpgsql
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
    FOR UPDATE;

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

CREATE TRIGGER trg_payment_amount_balance_guard
AFTER UPDATE OF amount_minor ON payments
FOR EACH ROW EXECUTE FUNCTION enforce_payment_balance_conservation();

CREATE TRIGGER trg_payment_allocations_balance_guard
AFTER INSERT OR UPDATE ON payment_allocations
FOR EACH ROW EXECUTE FUNCTION enforce_payment_balance_conservation();

CREATE TRIGGER trg_payment_refunds_balance_guard
AFTER INSERT OR UPDATE ON payment_refunds
FOR EACH ROW EXECUTE FUNCTION enforce_payment_balance_conservation();
