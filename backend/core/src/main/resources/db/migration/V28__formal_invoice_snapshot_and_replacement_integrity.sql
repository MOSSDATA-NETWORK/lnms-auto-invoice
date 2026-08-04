-- Formal line items and adjustments must be exact copies of the approved
-- preview. A correction only replaces its origin after the replacement PDF
-- has been confirmed in the same transaction.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.invoice_items formal
        JOIN public.invoices invoice
          ON invoice.tenant_id = formal.tenant_id
         AND invoice.id = formal.invoice_id
        LEFT JOIN public.invoice_preview_items source
          ON source.tenant_id = formal.tenant_id
         AND source.id = formal.source_preview_item_id
        LEFT JOIN public.invoice_preview_exclusions exclusion
          ON exclusion.tenant_id = source.tenant_id
         AND exclusion.invoice_preview_id = source.invoice_preview_id
         AND exclusion.invoice_preview_item_id = source.id
        WHERE source.id IS NULL
           OR source.invoice_preview_id IS DISTINCT FROM invoice.source_preview_id
           OR exclusion.id IS NOT NULL
           OR ROW(
                formal.contract_item_id, formal.service_id, formal.pricing_rule_version_id,
                formal.usage_snapshot_id, formal.source_key, formal.line_no,
                formal.item_name, formal.item_description,
                formal.billing_period_start, formal.billing_period_end,
                formal.raw_usage, formal.converted_usage, formal.rounded_usage,
                formal.billing_usage, formal.quantity, formal.unit, formal.unit_price,
                formal.subtotal_minor, formal.discount_minor, formal.tax_minor,
                formal.total_minor, formal.calculation_snapshot_json, formal.display_json
              ) IS DISTINCT FROM ROW(
                source.contract_item_id, source.service_id, source.pricing_rule_version_id,
                source.usage_snapshot_id, source.source_key, source.line_no,
                source.item_name, source.item_description,
                source.billing_period_start, source.billing_period_end,
                source.raw_usage, source.converted_usage, source.rounded_usage,
                source.billing_usage, source.quantity, source.unit, source.unit_price,
                source.subtotal_minor, source.discount_minor, source.tax_minor,
                source.total_minor, source.calculation_snapshot_json, source.display_json
              )
    ) OR EXISTS (
        SELECT 1
        FROM public.invoices invoice
        JOIN public.invoice_preview_items source
          ON source.tenant_id = invoice.tenant_id
         AND source.invoice_preview_id = invoice.source_preview_id
        LEFT JOIN public.invoice_preview_exclusions exclusion
          ON exclusion.tenant_id = source.tenant_id
         AND exclusion.invoice_preview_id = source.invoice_preview_id
         AND exclusion.invoice_preview_item_id = source.id
        LEFT JOIN public.invoice_items formal
          ON formal.tenant_id = invoice.tenant_id
         AND formal.invoice_id = invoice.id
         AND formal.source_preview_item_id = source.id
        WHERE exclusion.id IS NULL
          AND formal.id IS NULL
    ) THEN
        RAISE EXCEPTION 'existing formal invoice items do not exactly match their source previews'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_items_preview_snapshot';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.invoice_adjustments formal
        JOIN public.invoices invoice
          ON invoice.tenant_id = formal.tenant_id
         AND invoice.id = formal.invoice_id
        LEFT JOIN public.invoice_preview_adjustments source
          ON source.tenant_id = formal.tenant_id
         AND source.id = formal.source_preview_adjustment_id
        WHERE source.id IS NULL
           OR source.invoice_preview_id IS DISTINCT FROM invoice.source_preview_id
           OR source.status <> 'ACTIVE'
           OR ROW(
                formal.adjustment_type, formal.description, formal.amount_minor,
                formal.tax_rate, formal.included_in_tax_base, formal.reason,
                formal.attachment_file_id, formal.operator_snapshot_json
              ) IS DISTINCT FROM ROW(
                source.adjustment_type, source.description, source.amount_minor,
                source.tax_rate, source.included_in_tax_base, source.reason,
                source.attachment_file_id,
                jsonb_build_object(
                    'created_by', source.created_by,
                    'approved_by', source.approved_by,
                    'created_at', source.created_at
                )
              )
    ) OR EXISTS (
        SELECT 1
        FROM public.invoices invoice
        JOIN public.invoice_preview_adjustments source
          ON source.tenant_id = invoice.tenant_id
         AND source.invoice_preview_id = invoice.source_preview_id
         AND source.status = 'ACTIVE'
        LEFT JOIN public.invoice_adjustments formal
          ON formal.tenant_id = invoice.tenant_id
         AND formal.invoice_id = invoice.id
         AND formal.source_preview_adjustment_id = source.id
        WHERE formal.id IS NULL
    ) THEN
        RAISE EXCEPTION 'existing formal invoice adjustments do not exactly match their source previews'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_adjustments_preview_snapshot';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.invoice_relations relation
        JOIN public.invoices source
          ON source.tenant_id = relation.tenant_id
         AND source.id = relation.source_invoice_id
        JOIN public.invoices target
          ON target.tenant_id = relation.tenant_id
         AND target.id = relation.target_invoice_id
        JOIN public.invoice_previews preview
          ON preview.tenant_id = target.tenant_id
         AND preview.id = target.source_preview_id
        WHERE relation.relation_type = 'REPLACES'
          AND (source.document_status <> 'REPLACED'
               OR target.document_status = 'FINALIZING'
               OR preview.origin_invoice_id IS DISTINCT FROM source.id)
    ) THEN
        RAISE EXCEPTION 'existing replacement relations are inconsistent with correction lifecycle state'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_relations_replaces_lifecycle';
    END IF;
END
$$;

ALTER TABLE public.invoice_items
    ALTER COLUMN source_preview_item_id SET NOT NULL;

ALTER TABLE public.invoice_adjustments
    ALTER COLUMN source_preview_adjustment_id SET NOT NULL;

ALTER TABLE public.invoice_items
    ADD CONSTRAINT uq_invoice_items_source_preview
        UNIQUE (invoice_id, source_preview_item_id);

ALTER TABLE public.invoice_adjustments
    ADD CONSTRAINT uq_invoice_adjustments_source_preview
        UNIQUE (invoice_id, source_preview_adjustment_id);

CREATE OR REPLACE FUNCTION public.protect_finalizing_preview_child()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    old_tenant_id uuid;
    old_preview_id uuid;
    new_tenant_id uuid;
    new_preview_id uuid;
    locked_preview record;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_tenant_id := OLD.tenant_id;
        old_preview_id := OLD.invoice_preview_id;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_tenant_id := NEW.tenant_id;
        new_preview_id := NEW.invoice_preview_id;
    END IF;

    FOR locked_preview IN
        SELECT preview.id, preview.status
        FROM public.invoice_previews preview
        WHERE (old_preview_id IS NOT NULL
               AND preview.tenant_id = old_tenant_id
               AND preview.id = old_preview_id)
           OR (new_preview_id IS NOT NULL
               AND preview.tenant_id = new_tenant_id
               AND preview.id = new_preview_id)
        ORDER BY preview.id
        FOR UPDATE
    LOOP
        IF locked_preview.status IN ('FINALIZING', 'FINALIZED') THEN
            RAISE EXCEPTION 'a finalizing or finalized preview snapshot cannot be changed'
                USING ERRCODE = '55000';
        END IF;
    END LOOP;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION public.validate_formal_invoice_item_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    source_preview_id uuid;
    invoice_status text;
    source public.invoice_preview_items%ROWTYPE;
BEGIN
    IF NEW.source_preview_item_id IS NULL THEN
        RAISE EXCEPTION 'formal invoice item requires a source preview item'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_items_preview_snapshot';
    END IF;

    SELECT invoice.source_preview_id, invoice.document_status
    INTO source_preview_id, invoice_status
    FROM public.invoices invoice
    WHERE invoice.tenant_id = NEW.tenant_id
      AND invoice.id = NEW.invoice_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'formal invoice item parent does not exist in the tenant'
            USING ERRCODE = '23503';
    END IF;
    IF invoice_status <> 'FINALIZING' THEN
        RAISE EXCEPTION 'formal invoice items can only be inserted while the invoice is finalizing'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_items_finalizing_insert';
    END IF;

    SELECT item.*
    INTO source
    FROM public.invoice_preview_items item
    WHERE item.tenant_id = NEW.tenant_id
      AND item.id = NEW.source_preview_item_id
    FOR SHARE;
    IF NOT FOUND OR source.invoice_preview_id IS DISTINCT FROM source_preview_id
            OR EXISTS (
                SELECT 1
                FROM public.invoice_preview_exclusions exclusion
                WHERE exclusion.tenant_id = NEW.tenant_id
                  AND exclusion.invoice_preview_id = source_preview_id
                  AND exclusion.invoice_preview_item_id = NEW.source_preview_item_id
            ) THEN
        RAISE EXCEPTION 'formal invoice item source is not an included item of the approved preview'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_items_preview_source';
    END IF;

    IF ROW(
        NEW.contract_item_id, NEW.service_id, NEW.pricing_rule_version_id,
        NEW.usage_snapshot_id, NEW.source_key, NEW.line_no,
        NEW.item_name, NEW.item_description,
        NEW.billing_period_start, NEW.billing_period_end,
        NEW.raw_usage, NEW.converted_usage, NEW.rounded_usage,
        NEW.billing_usage, NEW.quantity, NEW.unit, NEW.unit_price,
        NEW.subtotal_minor, NEW.discount_minor, NEW.tax_minor,
        NEW.total_minor, NEW.calculation_snapshot_json, NEW.display_json
    ) IS DISTINCT FROM ROW(
        source.contract_item_id, source.service_id, source.pricing_rule_version_id,
        source.usage_snapshot_id, source.source_key, source.line_no,
        source.item_name, source.item_description,
        source.billing_period_start, source.billing_period_end,
        source.raw_usage, source.converted_usage, source.rounded_usage,
        source.billing_usage, source.quantity, source.unit, source.unit_price,
        source.subtotal_minor, source.discount_minor, source.tax_minor,
        source.total_minor, source.calculation_snapshot_json, source.display_json
    ) THEN
        RAISE EXCEPTION 'formal invoice item differs from its approved preview item'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_items_preview_snapshot';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION public.validate_formal_invoice_adjustment_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    source_preview_id uuid;
    invoice_status text;
    source public.invoice_preview_adjustments%ROWTYPE;
    expected_operator_snapshot jsonb;
BEGIN
    IF NEW.source_preview_adjustment_id IS NULL THEN
        RAISE EXCEPTION 'formal invoice adjustment requires a source preview adjustment'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_adjustments_preview_snapshot';
    END IF;

    SELECT invoice.source_preview_id, invoice.document_status
    INTO source_preview_id, invoice_status
    FROM public.invoices invoice
    WHERE invoice.tenant_id = NEW.tenant_id
      AND invoice.id = NEW.invoice_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'formal invoice adjustment parent does not exist in the tenant'
            USING ERRCODE = '23503';
    END IF;
    IF invoice_status <> 'FINALIZING' THEN
        RAISE EXCEPTION 'formal invoice adjustments can only be inserted while the invoice is finalizing'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_adjustments_finalizing_insert';
    END IF;

    SELECT adjustment.*
    INTO source
    FROM public.invoice_preview_adjustments adjustment
    WHERE adjustment.tenant_id = NEW.tenant_id
      AND adjustment.id = NEW.source_preview_adjustment_id
    FOR SHARE;
    IF NOT FOUND OR source.invoice_preview_id IS DISTINCT FROM source_preview_id
            OR source.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'formal invoice adjustment source is not active on the approved preview'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_adjustments_preview_source';
    END IF;

    expected_operator_snapshot := jsonb_build_object(
        'created_by', source.created_by,
        'approved_by', source.approved_by,
        'created_at', source.created_at
    );
    IF ROW(
        NEW.adjustment_type, NEW.description, NEW.amount_minor, NEW.tax_rate,
        NEW.included_in_tax_base, NEW.reason, NEW.attachment_file_id,
        NEW.operator_snapshot_json
    ) IS DISTINCT FROM ROW(
        source.adjustment_type, source.description, source.amount_minor, source.tax_rate,
        source.included_in_tax_base, source.reason, source.attachment_file_id,
        expected_operator_snapshot
    ) THEN
        RAISE EXCEPTION 'formal invoice adjustment differs from its approved preview adjustment'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_adjustments_preview_snapshot';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION public.validate_invoice_confirmation_and_replacement()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
BEGIN
    IF OLD.document_status = 'FINALIZING' AND NEW.document_status = 'CONFIRMED' THEN
        IF EXISTS (
            SELECT 1
            FROM (
                SELECT item.id AS source_id
                FROM public.invoice_preview_items item
                LEFT JOIN public.invoice_preview_exclusions exclusion
                  ON exclusion.tenant_id = item.tenant_id
                 AND exclusion.invoice_preview_id = item.invoice_preview_id
                 AND exclusion.invoice_preview_item_id = item.id
                WHERE item.tenant_id = NEW.tenant_id
                  AND item.invoice_preview_id = NEW.source_preview_id
                  AND exclusion.id IS NULL
                EXCEPT
                SELECT formal.source_preview_item_id
                FROM public.invoice_items formal
                WHERE formal.tenant_id = NEW.tenant_id
                  AND formal.invoice_id = NEW.id
            ) missing_item
        ) OR EXISTS (
            SELECT 1
            FROM (
                SELECT formal.source_preview_item_id AS source_id
                FROM public.invoice_items formal
                WHERE formal.tenant_id = NEW.tenant_id
                  AND formal.invoice_id = NEW.id
                EXCEPT
                SELECT item.id
                FROM public.invoice_preview_items item
                LEFT JOIN public.invoice_preview_exclusions exclusion
                  ON exclusion.tenant_id = item.tenant_id
                 AND exclusion.invoice_preview_id = item.invoice_preview_id
                 AND exclusion.invoice_preview_item_id = item.id
                WHERE item.tenant_id = NEW.tenant_id
                  AND item.invoice_preview_id = NEW.source_preview_id
                  AND exclusion.id IS NULL
            ) extra_item
        ) THEN
            RAISE EXCEPTION 'formal invoice items do not completely cover the approved preview'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_items_preview_complete';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM (
                SELECT adjustment.id AS source_id
                FROM public.invoice_preview_adjustments adjustment
                WHERE adjustment.tenant_id = NEW.tenant_id
                  AND adjustment.invoice_preview_id = NEW.source_preview_id
                  AND adjustment.status = 'ACTIVE'
                EXCEPT
                SELECT formal.source_preview_adjustment_id
                FROM public.invoice_adjustments formal
                WHERE formal.tenant_id = NEW.tenant_id
                  AND formal.invoice_id = NEW.id
            ) missing_adjustment
        ) OR EXISTS (
            SELECT 1
            FROM (
                SELECT formal.source_preview_adjustment_id AS source_id
                FROM public.invoice_adjustments formal
                WHERE formal.tenant_id = NEW.tenant_id
                  AND formal.invoice_id = NEW.id
                EXCEPT
                SELECT adjustment.id
                FROM public.invoice_preview_adjustments adjustment
                WHERE adjustment.tenant_id = NEW.tenant_id
                  AND adjustment.invoice_preview_id = NEW.source_preview_id
                  AND adjustment.status = 'ACTIVE'
            ) extra_adjustment
        ) THEN
            RAISE EXCEPTION 'formal invoice adjustments do not completely cover the approved preview'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_adjustments_preview_complete';
        END IF;
    END IF;

    IF OLD.document_status = 'VOIDED' AND NEW.document_status = 'REPLACED'
            AND NOT EXISTS (
                SELECT 1
                FROM public.invoice_relations relation
                JOIN public.invoices target
                  ON target.tenant_id = relation.tenant_id
                 AND target.id = relation.target_invoice_id
                JOIN public.invoice_previews preview
                  ON preview.tenant_id = target.tenant_id
                 AND preview.id = target.source_preview_id
                WHERE relation.tenant_id = NEW.tenant_id
                  AND relation.source_invoice_id = NEW.id
                  AND relation.relation_type = 'REPLACES'
                  AND target.document_status <> 'FINALIZING'
                  AND preview.origin_invoice_id = NEW.id
            ) THEN
        RAISE EXCEPTION 'an invoice can only become replaced through a confirmed correction invoice'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_replaced_relation';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION public.validate_replaces_relation_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    source_status text;
    target_status text;
    target_origin_invoice_id uuid;
BEGIN
    IF NEW.relation_type <> 'REPLACES' THEN
        RETURN NEW;
    END IF;

    PERFORM invoice.id
    FROM public.invoices invoice
    WHERE invoice.tenant_id = NEW.tenant_id
      AND invoice.id IN (NEW.source_invoice_id, NEW.target_invoice_id)
    ORDER BY invoice.id
    FOR UPDATE;

    SELECT source.document_status, target.document_status, preview.origin_invoice_id
    INTO source_status, target_status, target_origin_invoice_id
    FROM public.invoices source
    JOIN public.invoices target
      ON target.tenant_id = source.tenant_id
     AND target.id = NEW.target_invoice_id
    JOIN public.invoice_previews preview
      ON preview.tenant_id = target.tenant_id
     AND preview.id = target.source_preview_id
    WHERE source.tenant_id = NEW.tenant_id
      AND source.id = NEW.source_invoice_id;

    IF NOT FOUND
            OR source_status <> 'VOIDED'
            OR target_status = 'FINALIZING'
            OR target_origin_invoice_id IS DISTINCT FROM NEW.source_invoice_id THEN
        RAISE EXCEPTION 'REPLACES must connect a voided origin to its confirmed correction invoice'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoice_relations_replaces_lifecycle';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION public.require_completed_correction_replacement()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    origin_invoice_id uuid;
BEGIN
    IF NEW.document_status IS NOT DISTINCT FROM OLD.document_status
            OR NEW.document_status = 'FINALIZING' THEN
        RETURN NULL;
    END IF;

    SELECT preview.origin_invoice_id
    INTO origin_invoice_id
    FROM public.invoice_previews preview
    WHERE preview.tenant_id = NEW.tenant_id
      AND preview.id = NEW.source_preview_id;
    IF origin_invoice_id IS NULL THEN
        RETURN NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.invoice_relations relation
        JOIN public.invoices origin
          ON origin.tenant_id = relation.tenant_id
         AND origin.id = relation.source_invoice_id
        WHERE relation.tenant_id = NEW.tenant_id
          AND relation.source_invoice_id = origin_invoice_id
          AND relation.target_invoice_id = NEW.id
          AND relation.relation_type = 'REPLACES'
          AND origin.document_status = 'REPLACED'
    ) THEN
        RAISE EXCEPTION 'confirmed correction invoice requires a completed replacement relation'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_invoices_correction_replacement_complete';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_preview_items_protect_final_snapshot
BEFORE INSERT OR UPDATE OR DELETE ON public.invoice_preview_items
FOR EACH ROW EXECUTE FUNCTION public.protect_finalizing_preview_child();

CREATE TRIGGER trg_preview_adjustments_protect_final_snapshot
BEFORE INSERT OR UPDATE OR DELETE ON public.invoice_preview_adjustments
FOR EACH ROW EXECUTE FUNCTION public.protect_finalizing_preview_child();

CREATE TRIGGER trg_preview_exclusions_protect_final_snapshot
BEFORE INSERT OR UPDATE OR DELETE ON public.invoice_preview_exclusions
FOR EACH ROW EXECUTE FUNCTION public.protect_finalizing_preview_child();

CREATE TRIGGER trg_invoice_items_check_preview_snapshot
BEFORE INSERT ON public.invoice_items
FOR EACH ROW EXECUTE FUNCTION public.validate_formal_invoice_item_insert();

CREATE TRIGGER trg_invoice_adjustments_check_preview_snapshot
BEFORE INSERT ON public.invoice_adjustments
FOR EACH ROW EXECUTE FUNCTION public.validate_formal_invoice_adjustment_insert();

CREATE TRIGGER trg_invoices_validate_confirmation_and_replacement
BEFORE UPDATE OF document_status ON public.invoices
FOR EACH ROW EXECUTE FUNCTION public.validate_invoice_confirmation_and_replacement();

CREATE TRIGGER trg_invoice_relations_validate_replaces
BEFORE INSERT ON public.invoice_relations
FOR EACH ROW EXECUTE FUNCTION public.validate_replaces_relation_insert();

CREATE CONSTRAINT TRIGGER trg_invoices_require_completed_correction
AFTER UPDATE ON public.invoices
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION public.require_completed_correction_replacement();
