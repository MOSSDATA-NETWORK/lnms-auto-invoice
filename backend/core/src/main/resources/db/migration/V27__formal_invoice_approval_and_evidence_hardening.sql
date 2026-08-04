-- A formal invoice must be an exact frozen copy of the preview revision that
-- was approved immediately before finalization. Lifecycle timestamps and
-- derived states must remain truthful after the invoice is created.

CREATE OR REPLACE FUNCTION public.lock_frozen_file_row(p_tenant_id uuid, p_file_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    target_deleted_at timestamptz;
BEGIN
    IF p_file_id IS NULL THEN
        RETURN;
    END IF;

    SELECT file.deleted_at
    INTO target_deleted_at
    FROM public.files file
    WHERE file.tenant_id = p_tenant_id
      AND file.id = p_file_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'file % does not exist in tenant %', p_file_id, p_tenant_id
            USING ERRCODE = '23503';
    END IF;
    IF target_deleted_at IS NOT NULL THEN
        RAISE EXCEPTION 'deleted file % cannot become immutable evidence', p_file_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_frozen_file_reference_not_deleted';
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION public.lock_approval_history_for_formal_invoice()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    preview public.invoice_previews%ROWTYPE;
    approval public.approval_instances%ROWTYPE;
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

    SELECT source.*
    INTO preview
    FROM public.invoice_previews source
    WHERE source.tenant_id = NEW.tenant_id
      AND source.id = NEW.source_preview_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'formal invoice source preview does not exist in the tenant'
            USING ERRCODE = '23503', CONSTRAINT = 'fk_invoices_source_preview';
    END IF;

    SELECT source.*
    INTO approval
    FROM public.approval_instances source
    WHERE source.tenant_id = NEW.tenant_id
      AND source.id = NEW.approval_instance_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'formal invoice approval instance does not exist in the tenant'
            USING ERRCODE = '23503', CONSTRAINT = 'fk_invoices_approval_instance';
    END IF;

    IF preview.status <> 'FINALIZING'
            OR preview.approved_at IS NULL
            OR preview.version <= 0
            OR approval.status <> 'APPROVED'
            OR approval.invoice_preview_id IS DISTINCT FROM NEW.source_preview_id
            OR approval.workflow_version_id IS DISTINCT FROM preview.approval_workflow_version_id
            OR approval.preview_version IS DISTINCT FROM preview.version - 1
            OR approval.approval_revision IS DISTINCT FROM preview.approval_revision THEN
        RAISE EXCEPTION 'formal invoice approval does not match the finalized preview revision'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_approval_binding';
    END IF;

    IF NEW.invoice_profile_id IS DISTINCT FROM preview.invoice_profile_id
            OR NEW.customer_id IS DISTINCT FROM preview.customer_id
            OR NEW.company_id IS DISTINCT FROM preview.company_id
            OR NEW.template_id IS DISTINCT FROM preview.template_id
            OR NEW.template_version_id IS DISTINCT FROM preview.template_version_id
            OR NEW.period_start IS DISTINCT FROM preview.period_start
            OR NEW.period_end IS DISTINCT FROM preview.period_end
            OR NEW.issue_date IS DISTINCT FROM preview.issue_date
            OR NEW.due_date IS DISTINCT FROM preview.due_date
            OR NEW.timezone IS DISTINCT FROM preview.timezone
            OR NEW.language IS DISTINCT FROM preview.language
            OR NEW.currency_code IS DISTINCT FROM preview.currency_code
            OR NEW.exchange_rate IS DISTINCT FROM preview.exchange_rate
            OR NEW.subtotal_minor IS DISTINCT FROM preview.subtotal_minor
            OR NEW.discount_minor IS DISTINCT FROM preview.discount_minor
            OR NEW.tax_minor IS DISTINCT FROM preview.tax_minor
            OR NEW.adjustment_minor IS DISTINCT FROM preview.adjustment_minor
            OR NEW.total_minor IS DISTINCT FROM preview.total_minor
            OR NEW.party_snapshot_json IS DISTINCT FROM preview.party_snapshot_json
            OR NEW.profile_snapshot_json IS DISTINCT FROM preview.profile_snapshot_json
            OR NEW.render_model_json IS DISTINCT FROM preview.render_model_json THEN
        RAISE EXCEPTION 'formal invoice header differs from its approved preview snapshot'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_preview_snapshot';
    END IF;

    RETURN NEW;
END
$$;

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
    active_allocated_minor numeric;
    observed_at timestamptz := clock_timestamp();
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'formal invoice cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF (to_jsonb(NEW) - allowed_columns) IS DISTINCT FROM (to_jsonb(OLD) - allowed_columns) THEN
        RAISE EXCEPTION 'frozen invoice fields cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.version <> OLD.version + 1
            OR NEW.updated_at < OLD.updated_at
            OR NEW.updated_at > observed_at THEN
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
                  AND file.deleted_at IS NULL
            ) THEN
        RAISE EXCEPTION 'formal invoice confirmation requires complete immutable PDF evidence'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_confirmation_evidence';
    END IF;

    IF OLD.confirmed_at IS NOT NULL
            AND NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at THEN
        RAISE EXCEPTION 'invoice confirmed_at is immutable once recorded'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_confirmed_at_immutable';
    END IF;
    IF OLD.confirmed_at IS NULL AND NEW.confirmed_at IS NOT NULL AND NOT (
            OLD.document_status = 'FINALIZING' AND NEW.document_status = 'CONFIRMED') THEN
        RAISE EXCEPTION 'invoice confirmed_at can only be recorded during confirmation'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_confirmed_at_transition';
    END IF;
    IF (NEW.document_status IN ('CONFIRMED', 'SENT') AND NEW.confirmed_at IS NULL)
            OR (NEW.document_status = 'FINALIZING' AND NEW.confirmed_at IS NOT NULL) THEN
        RAISE EXCEPTION 'invoice confirmed_at is inconsistent with document status'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_confirmed_at_state';
    END IF;

    IF OLD.sent_at IS NOT NULL AND NEW.sent_at IS DISTINCT FROM OLD.sent_at THEN
        RAISE EXCEPTION 'invoice sent_at is immutable once recorded'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_sent_at_immutable';
    END IF;
    IF OLD.sent_at IS NULL AND NEW.sent_at IS NOT NULL AND NOT (
            OLD.document_status = 'CONFIRMED'
            AND NEW.document_status = 'SENT'
            AND NEW.send_status IN ('PARTIALLY_SENT', 'SENT')) THEN
        RAISE EXCEPTION 'invoice sent_at can only be recorded with the first successful delivery'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_sent_at_transition';
    END IF;
    IF NEW.document_status = 'FINALIZING'
            AND (NEW.send_status <> 'NOT_QUEUED' OR NEW.sent_at IS NOT NULL) THEN
        RAISE EXCEPTION 'finalizing invoice cannot enter the notification lifecycle'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_finalizing_send_state';
    END IF;
    IF NEW.document_status = 'CONFIRMED'
            AND (NEW.send_status IN ('PARTIALLY_SENT', 'SENT') OR NEW.sent_at IS NOT NULL) THEN
        RAISE EXCEPTION 'successful delivery must advance the invoice document status to SENT'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_confirmed_send_state';
    END IF;
    IF NEW.document_status = 'SENT' AND NEW.sent_at IS NULL THEN
        RAISE EXCEPTION 'sent invoice state requires sent_at'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_sent_at_state';
    END IF;
    IF (OLD.document_status IN ('VOIDED', 'REPLACED')
            OR (OLD.document_status <> 'VOIDED' AND NEW.document_status = 'VOIDED'))
            AND (NEW.send_status IS DISTINCT FROM OLD.send_status
                OR NEW.sent_at IS DISTINCT FROM OLD.sent_at) THEN
        RAISE EXCEPTION 'terminal invoice transition cannot rewrite delivery history'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_terminal_send_history';
    END IF;

    IF OLD.voided_at IS NOT NULL AND NEW.voided_at IS DISTINCT FROM OLD.voided_at THEN
        RAISE EXCEPTION 'invoice voided_at is immutable once recorded'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_voided_at_immutable';
    END IF;
    IF OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL
            AND NOT (OLD.document_status <> 'VOIDED' AND NEW.document_status = 'VOIDED') THEN
        RAISE EXCEPTION 'invoice voided_at can only be recorded during voiding'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_voided_at_transition';
    END IF;
    IF (NEW.document_status IN ('VOIDED', 'REPLACED') AND NEW.voided_at IS NULL)
            OR (NEW.document_status NOT IN ('VOIDED', 'REPLACED') AND NEW.voided_at IS NOT NULL) THEN
        RAISE EXCEPTION 'invoice voided_at is inconsistent with document status'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_voided_at_state';
    END IF;

    IF NEW.confirmed_at IS NOT NULL
            AND (NEW.confirmed_at < NEW.created_at OR NEW.confirmed_at > observed_at) THEN
        RAISE EXCEPTION 'invoice confirmed_at is outside the lifecycle timeline'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_confirmed_at_timeline';
    END IF;
    IF NEW.sent_at IS NOT NULL
            AND (NEW.confirmed_at IS NULL OR NEW.sent_at < NEW.confirmed_at OR NEW.sent_at > observed_at) THEN
        RAISE EXCEPTION 'invoice sent_at is outside the lifecycle timeline'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_sent_at_timeline';
    END IF;
    IF NEW.voided_at IS NOT NULL
            AND (NEW.voided_at < COALESCE(NEW.sent_at, NEW.confirmed_at, NEW.created_at)
                OR NEW.voided_at > observed_at) THEN
        RAISE EXCEPTION 'invoice voided_at is outside the lifecycle timeline'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_voided_at_timeline';
    END IF;

    IF NEW.payment_status IS DISTINCT FROM OLD.payment_status
            OR NEW.paid_at IS DISTINCT FROM OLD.paid_at THEN
        SELECT COALESCE(sum(allocation.amount_minor), 0)
        INTO active_allocated_minor
        FROM public.payment_allocations allocation
        WHERE allocation.tenant_id = NEW.tenant_id
          AND allocation.invoice_id = NEW.id
          AND allocation.status = 'ACTIVE';

        IF (NEW.payment_status = 'UNPAID' AND active_allocated_minor <> 0)
                OR (NEW.payment_status = 'PARTIALLY_PAID'
                    AND NOT (active_allocated_minor > 0 AND active_allocated_minor < NEW.total_minor))
                OR (NEW.payment_status = 'PAID' AND active_allocated_minor <> NEW.total_minor)
                OR (NEW.payment_status = 'OVERDUE' AND NOT (
                    active_allocated_minor < NEW.total_minor
                    AND (observed_at AT TIME ZONE NEW.timezone)::date > NEW.due_date
                )) THEN
            RAISE EXCEPTION 'invoice payment status does not match active payment allocations'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_payment_status_derived';
        END IF;
    END IF;

    IF (NEW.payment_status = 'PAID' AND NEW.paid_at IS NULL)
            OR (NEW.payment_status <> 'PAID' AND NEW.paid_at IS NOT NULL) THEN
        RAISE EXCEPTION 'invoice paid_at is inconsistent with payment status'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_paid_at_state';
    END IF;
    IF OLD.payment_status = 'PAID' AND NEW.payment_status = 'PAID'
            AND NEW.paid_at IS DISTINCT FROM OLD.paid_at THEN
        RAISE EXCEPTION 'invoice paid_at is immutable while the invoice remains paid'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_paid_at_immutable';
    END IF;
    IF NEW.paid_at IS NOT NULL
            AND (NEW.confirmed_at IS NULL OR NEW.paid_at < NEW.confirmed_at OR NEW.paid_at > observed_at) THEN
        RAISE EXCEPTION 'invoice paid_at is outside the lifecycle timeline'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_paid_at_timeline';
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
