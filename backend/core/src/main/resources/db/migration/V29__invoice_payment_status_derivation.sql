-- Payment allocations are the source of truth for both payment and invoice
-- status. Refresh the invoice in the same trigger transaction so direct SQL
-- cannot leave invoices permanently stale.

CREATE OR REPLACE FUNCTION public.refresh_payment_status_from_history()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    target_tenant_id uuid;
    target_payment_id uuid;
    target_invoice_id uuid;
    payment_amount bigint;
    allocated_minor numeric;
    refunded_minor numeric;
    derived_status text;
    invoice_total_minor bigint;
    invoice_due_date date;
    invoice_timezone text;
    invoice_document_status text;
    invoice_allocated_minor numeric;
    invoice_status text;
    invoice_paid_at timestamptz;
    derived_invoice_status text;
    derived_invoice_paid_at timestamptz;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;

    target_tenant_id := NEW.tenant_id;
    target_payment_id := NEW.payment_id;
    IF TG_TABLE_NAME = 'payment_allocations' THEN
        target_invoice_id := NEW.invoice_id;
    END IF;

    SELECT payment.amount_minor
    INTO payment_amount
    FROM public.payments payment
    WHERE payment.tenant_id = target_tenant_id
      AND payment.id = target_payment_id
    FOR NO KEY UPDATE;

    IF payment_amount IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT COALESCE(sum(allocation.amount_minor), 0)
    INTO allocated_minor
    FROM public.payment_allocations allocation
    WHERE allocation.tenant_id = target_tenant_id
      AND allocation.payment_id = target_payment_id
      AND allocation.status = 'ACTIVE';

    SELECT COALESCE(sum(refund.amount_minor), 0)
    INTO refunded_minor
    FROM public.payment_refunds refund
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

    UPDATE public.payments
    SET status = derived_status,
        updated_at = clock_timestamp(),
        version = version + 1
    WHERE tenant_id = target_tenant_id
      AND id = target_payment_id;

    IF target_invoice_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT invoice.total_minor, invoice.due_date, invoice.timezone,
           invoice.document_status, invoice.payment_status, invoice.paid_at
    INTO invoice_total_minor, invoice_due_date, invoice_timezone,
         invoice_document_status, invoice_status, invoice_paid_at
    FROM public.invoices invoice
    WHERE invoice.tenant_id = target_tenant_id
      AND invoice.id = target_invoice_id
    FOR NO KEY UPDATE;

    IF invoice_total_minor IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT COALESCE(sum(allocation.amount_minor), 0)
    INTO invoice_allocated_minor
    FROM public.payment_allocations allocation
    WHERE allocation.tenant_id = target_tenant_id
      AND allocation.invoice_id = target_invoice_id
      AND allocation.status = 'ACTIVE';

    IF invoice_allocated_minor > invoice_total_minor THEN
        RAISE EXCEPTION 'payment allocations exceed invoice total'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_invoice_outstanding';
    END IF;

    derived_invoice_status := CASE
        WHEN invoice_allocated_minor = invoice_total_minor THEN 'PAID'
        WHEN (clock_timestamp() AT TIME ZONE invoice_timezone)::date > invoice_due_date THEN 'OVERDUE'
        WHEN invoice_allocated_minor > 0 THEN 'PARTIALLY_PAID'
        ELSE 'UNPAID'
    END;
    derived_invoice_paid_at := CASE
        WHEN derived_invoice_status = 'PAID' THEN COALESCE(invoice_paid_at, clock_timestamp())
        ELSE NULL
    END;

    UPDATE public.invoices
    SET payment_status = derived_invoice_status,
        paid_at = derived_invoice_paid_at,
        updated_at = clock_timestamp(),
        version = version + 1
    WHERE tenant_id = target_tenant_id
      AND id = target_invoice_id;

    RETURN NEW;
END
$$;

WITH invoice_balances AS (
    SELECT invoice.id,
           invoice.tenant_id,
           invoice.total_minor,
           invoice.due_date,
           invoice.timezone,
           invoice.document_status,
           invoice.payment_status,
           invoice.paid_at,
           COALESCE(sum(allocation.amount_minor)
                    FILTER (WHERE allocation.status = 'ACTIVE'), 0) AS allocated_minor
    FROM public.invoices invoice
    LEFT JOIN public.payment_allocations allocation
      ON allocation.tenant_id = invoice.tenant_id
     AND allocation.invoice_id = invoice.id
    GROUP BY invoice.id
), derived AS (
    SELECT balance.*,
           CASE
               WHEN balance.document_status NOT IN ('CONFIRMED', 'SENT') THEN 'UNPAID'
               WHEN balance.allocated_minor = balance.total_minor THEN 'PAID'
               WHEN (clock_timestamp() AT TIME ZONE balance.timezone)::date > balance.due_date THEN 'OVERDUE'
               WHEN balance.allocated_minor > 0 THEN 'PARTIALLY_PAID'
               ELSE 'UNPAID'
           END AS derived_status
    FROM invoice_balances balance
)
UPDATE public.invoices invoice
SET payment_status = derived.derived_status,
    paid_at = CASE
        WHEN derived.derived_status = 'PAID' THEN COALESCE(invoice.paid_at, clock_timestamp())
        ELSE NULL
    END,
    updated_at = clock_timestamp(),
    version = invoice.version + 1
FROM derived
WHERE invoice.tenant_id = derived.tenant_id
  AND invoice.id = derived.id
  AND (invoice.payment_status IS DISTINCT FROM derived.derived_status
       OR (derived.derived_status = 'PAID' AND invoice.paid_at IS NULL)
       OR (derived.derived_status <> 'PAID' AND invoice.paid_at IS NOT NULL));
