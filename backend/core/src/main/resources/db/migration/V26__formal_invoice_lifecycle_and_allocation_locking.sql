-- Formal invoice state changes must preserve their evidence and audit trail.
-- Payment allocation inserts also join the global payment -> invoice lock order
-- so invoice voiding and new allocations cannot pass each other concurrently.

CREATE OR REPLACE FUNCTION public.protect_formal_invoice()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    allowed_columns text[] := ARRAY[
        'document_status', 'send_status', 'payment_status', 'updated_at', 'version',
        'confirmed_at', 'sent_at', 'voided_at', 'paid_at'
    ];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'formal invoice cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF (to_jsonb(NEW) - allowed_columns) IS DISTINCT FROM (to_jsonb(OLD) - allowed_columns) THEN
        RAISE EXCEPTION 'frozen invoice fields cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'formal invoice status updates require a monotonic version and timestamp'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_status_update';
    END IF;

    IF NEW.document_status <> OLD.document_status AND NOT (
        (OLD.document_status = 'FINALIZING' AND NEW.document_status IN ('CONFIRMED', 'VOIDED'))
        OR (OLD.document_status = 'CONFIRMED' AND NEW.document_status IN ('SENT', 'VOIDED'))
        OR (OLD.document_status = 'SENT' AND NEW.document_status = 'VOIDED')
        OR (OLD.document_status = 'VOIDED' AND NEW.document_status = 'REPLACED')
    ) THEN
        RAISE EXCEPTION 'invalid invoice document status transition: % -> %',
            OLD.document_status, NEW.document_status
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_document_status_transition';
    END IF;

    IF OLD.document_status = 'FINALIZING' AND NEW.document_status = 'CONFIRMED'
            AND NOT EXISTS (
                SELECT 1
                FROM public.invoice_files link
                JOIN public.files file
                  ON file.tenant_id = link.tenant_id
                 AND file.id = link.file_id
                WHERE link.tenant_id = NEW.tenant_id
                  AND link.invoice_id = NEW.id
                  AND link.file_role = 'PDF'
                  AND link.template_version_id = NEW.template_version_id
                  AND link.renderer_version IS NOT NULL
                  AND btrim(link.renderer_version) <> ''
                  AND link.chromium_version IS NOT NULL
                  AND btrim(link.chromium_version) <> ''
                  AND link.content_sha256 = file.sha256
                  AND file.mime_type = 'application/pdf'
                  AND file.file_size > 0
            ) THEN
        RAISE EXCEPTION 'formal invoice confirmation requires complete immutable PDF evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_confirmation_evidence';
    END IF;

    IF (NEW.document_status IN ('CONFIRMED', 'SENT') AND NEW.confirmed_at IS NULL)
            OR (NEW.document_status = 'FINALIZING' AND NEW.confirmed_at IS NOT NULL) THEN
        RAISE EXCEPTION 'invoice confirmed_at is inconsistent with document status'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_confirmed_at_state';
    END IF;

    IF (NEW.document_status = 'SENT' OR NEW.send_status IN ('PARTIALLY_SENT', 'SENT'))
            AND NEW.sent_at IS NULL THEN
        RAISE EXCEPTION 'sent invoice state requires sent_at'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_sent_at_state';
    END IF;

    IF (NEW.document_status IN ('VOIDED', 'REPLACED') AND NEW.voided_at IS NULL)
            OR (NEW.document_status NOT IN ('VOIDED', 'REPLACED') AND NEW.voided_at IS NOT NULL) THEN
        RAISE EXCEPTION 'invoice voided_at is inconsistent with document status'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_voided_at_state';
    END IF;

    IF (NEW.payment_status = 'PAID' AND NEW.paid_at IS NULL)
            OR (NEW.payment_status <> 'PAID' AND NEW.paid_at IS NOT NULL) THEN
        RAISE EXCEPTION 'invoice paid_at is inconsistent with payment status'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_paid_at_state';
    END IF;

    IF OLD.document_status <> 'VOIDED' AND NEW.document_status = 'VOIDED'
            AND EXISTS (
                SELECT 1
                FROM public.payment_allocations allocation
                WHERE allocation.tenant_id = NEW.tenant_id
                  AND allocation.invoice_id = NEW.id
                  AND allocation.status = 'ACTIVE'
            ) THEN
        RAISE EXCEPTION 'formal invoice with active payment allocations cannot be voided'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_void_active_payment';
    END IF;

    RETURN NEW;
END
$$;

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
    payment_customer_id uuid;
    payment_company_id uuid;
    payment_currency_code text;
    invoice_document_status text;
    invoice_customer_id uuid;
    invoice_company_id uuid;
    invoice_currency_code text;
    invoice_total_minor bigint;
    invoice_allocated_minor numeric;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE'
                OR NEW.reversed_by IS NOT NULL
                OR NEW.reversed_at IS NOT NULL
                OR NEW.reversal_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a new payment allocation must start ACTIVE without reversal metadata'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_initial_state';
        END IF;

        SELECT payment.customer_id, payment.company_id, payment.currency_code
        INTO payment_customer_id, payment_company_id, payment_currency_code
        FROM public.payments payment
        WHERE payment.tenant_id = NEW.tenant_id
          AND payment.id = NEW.payment_id
        FOR NO KEY UPDATE;

        SELECT invoice.document_status, invoice.customer_id, invoice.company_id,
               invoice.currency_code, invoice.total_minor
        INTO invoice_document_status, invoice_customer_id, invoice_company_id,
             invoice_currency_code, invoice_total_minor
        FROM public.invoices invoice
        WHERE invoice.tenant_id = NEW.tenant_id
          AND invoice.id = NEW.invoice_id
        FOR NO KEY UPDATE;

        IF invoice_document_status IS NULL
                OR invoice_document_status NOT IN ('CONFIRMED', 'SENT') THEN
            RAISE EXCEPTION 'payment allocations require a payable formal invoice'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_invoice_payable';
        END IF;

        IF payment_customer_id IS NULL
                OR payment_customer_id IS DISTINCT FROM invoice_customer_id
                OR (payment_company_id IS NOT NULL
                    AND payment_company_id IS DISTINCT FROM invoice_company_id)
                OR payment_currency_code IS DISTINCT FROM invoice_currency_code THEN
            RAISE EXCEPTION 'payment and invoice party or currency do not match'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_party_currency';
        END IF;

        SELECT COALESCE(sum(allocation.amount_minor), 0)
        INTO invoice_allocated_minor
        FROM public.payment_allocations allocation
        WHERE allocation.tenant_id = NEW.tenant_id
          AND allocation.invoice_id = NEW.invoice_id
          AND allocation.status = 'ACTIVE';

        IF invoice_allocated_minor + NEW.amount_minor > invoice_total_minor THEN
            RAISE EXCEPTION 'payment allocation exceeds invoice outstanding amount'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_invoice_outstanding';
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
