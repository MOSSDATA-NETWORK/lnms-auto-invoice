ALTER TABLE pricing_rule_versions
    ADD CONSTRAINT ck_pricing_version_unit_price_nonneg
        CHECK (unit_price IS NULL OR unit_price >= 0) NOT VALID,
    ADD CONSTRAINT ck_pricing_version_base_fee_nonneg
        CHECK (base_fee IS NULL OR base_fee >= 0) NOT VALID,
    ADD CONSTRAINT ck_pricing_version_committed_qty_nonneg
        CHECK (committed_quantity IS NULL OR committed_quantity >= 0) NOT VALID,
    ADD CONSTRAINT ck_pricing_version_overage_price_nonneg
        CHECK (overage_unit_price IS NULL OR overage_unit_price >= 0) NOT VALID,
    ADD CONSTRAINT ck_pricing_version_min_charge_nonneg
        CHECK (minimum_charge IS NULL OR minimum_charge >= 0) NOT VALID,
    ADD CONSTRAINT ck_pricing_version_max_charge_nonneg
        CHECK (maximum_charge IS NULL OR maximum_charge >= 0) NOT VALID,
    ADD CONSTRAINT ck_pricing_version_rounding_mode
        CHECK (rounding_mode IN (
            'NONE', 'DECIMAL_SCALE', 'HALF_UP_INTEGER', 'CEIL_INTEGER', 'CEIL_STEP'
        )) NOT VALID,
    ADD CONSTRAINT ck_pricing_version_rounding_scale
        CHECK (rounding_scale IS NULL OR rounding_scale BETWEEN 0 AND 12) NOT VALID,
    ADD CONSTRAINT ck_pricing_version_decimal_scale_required
        CHECK (rounding_mode <> 'DECIMAL_SCALE' OR rounding_scale IS NOT NULL) NOT VALID;

ALTER TABLE contract_items
    ADD CONSTRAINT ck_contract_items_default_quantity_nonneg
        CHECK (default_quantity IS NULL OR default_quantity >= 0) NOT VALID;

ALTER TABLE pricing_tiers
    ADD CONSTRAINT ck_pricing_tiers_lower_bound_nonneg
        CHECK (lower_bound >= 0) NOT VALID,
    ADD CONSTRAINT ck_pricing_tiers_unit_price_nonneg
        CHECK (unit_price >= 0) NOT VALID;

ALTER TABLE pricing_rule_versions VALIDATE CONSTRAINT ck_pricing_version_unit_price_nonneg;
ALTER TABLE pricing_rule_versions VALIDATE CONSTRAINT ck_pricing_version_base_fee_nonneg;
ALTER TABLE pricing_rule_versions VALIDATE CONSTRAINT ck_pricing_version_committed_qty_nonneg;
ALTER TABLE pricing_rule_versions VALIDATE CONSTRAINT ck_pricing_version_overage_price_nonneg;
ALTER TABLE pricing_rule_versions VALIDATE CONSTRAINT ck_pricing_version_min_charge_nonneg;
ALTER TABLE pricing_rule_versions VALIDATE CONSTRAINT ck_pricing_version_max_charge_nonneg;
ALTER TABLE pricing_rule_versions VALIDATE CONSTRAINT ck_pricing_version_rounding_mode;
ALTER TABLE pricing_rule_versions VALIDATE CONSTRAINT ck_pricing_version_rounding_scale;
ALTER TABLE pricing_rule_versions VALIDATE CONSTRAINT ck_pricing_version_decimal_scale_required;
ALTER TABLE contract_items VALIDATE CONSTRAINT ck_contract_items_default_quantity_nonneg;
ALTER TABLE pricing_tiers VALIDATE CONSTRAINT ck_pricing_tiers_lower_bound_nonneg;
ALTER TABLE pricing_tiers VALIDATE CONSTRAINT ck_pricing_tiers_unit_price_nonneg;

ALTER TABLE payment_allocations
    ADD CONSTRAINT ck_payment_allocations_reversal_state
        CHECK (
            (status = 'ACTIVE' AND reversed_by IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL)
            OR (status = 'REVERSED' AND reversed_by IS NOT NULL AND reversed_at IS NOT NULL
                AND reversal_reason IS NOT NULL)
        ) NOT VALID;

ALTER TABLE payment_allocations
    VALIDATE CONSTRAINT ck_payment_allocations_reversal_state;

CREATE OR REPLACE FUNCTION protect_published_version_child()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    child_tenant_id uuid;
    version_id uuid;
    version_status text;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        child_tenant_id := (to_jsonb(OLD) ->> 'tenant_id')::uuid;
        version_id := (to_jsonb(OLD) ->> TG_ARGV[1])::uuid;
        EXECUTE format(
            'SELECT status FROM public.%I WHERE tenant_id = $1 AND id = $2 FOR UPDATE',
            TG_ARGV[0]
        ) INTO version_status USING child_tenant_id, version_id;
        IF version_status IN ('PUBLISHED', 'RETIRED') THEN
            RAISE EXCEPTION 'children of published version % in % are immutable', version_id, TG_ARGV[0]
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        child_tenant_id := (to_jsonb(NEW) ->> 'tenant_id')::uuid;
        version_id := (to_jsonb(NEW) ->> TG_ARGV[1])::uuid;
        EXECUTE format(
            'SELECT status FROM public.%I WHERE tenant_id = $1 AND id = $2 FOR UPDATE',
            TG_ARGV[0]
        ) INTO version_status USING child_tenant_id, version_id;
        IF version_status IN ('PUBLISHED', 'RETIRED') THEN
            RAISE EXCEPTION 'children of published version % in % are immutable', version_id, TG_ARGV[0]
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_pricing_tiers_protect_published_version
BEFORE INSERT OR UPDATE OR DELETE ON pricing_tiers
FOR EACH ROW EXECUTE FUNCTION protect_published_version_child(
    'pricing_rule_versions', 'pricing_rule_version_id'
);

CREATE TRIGGER trg_template_assets_protect_published_version
BEFORE INSERT OR UPDATE OR DELETE ON invoice_template_assets
FOR EACH ROW EXECUTE FUNCTION protect_published_version_child(
    'invoice_template_versions', 'template_version_id'
);

CREATE TRIGGER trg_approval_steps_protect_published_version
BEFORE INSERT OR UPDATE OR DELETE ON approval_steps
FOR EACH ROW EXECUTE FUNCTION protect_published_version_child(
    'approval_workflow_versions', 'workflow_version_id'
);

CREATE OR REPLACE FUNCTION protect_approval_instance_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    allowed_columns text[] := ARRAY[
        'status', 'current_step_no', 'completed_at', 'invalidation_reason'
    ];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'approval instance history cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'completed approval instance is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF (to_jsonb(NEW) - allowed_columns) IS DISTINCT FROM (to_jsonb(OLD) - allowed_columns) THEN
        RAISE EXCEPTION 'approval instance identity and frozen context cannot be changed'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status = 'PENDING' AND NEW.completed_at IS NOT NULL THEN
        RAISE EXCEPTION 'pending approval instance cannot be completed'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.status <> 'PENDING' AND NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'terminal approval instance requires completed_at'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_approval_instances_protect_history
BEFORE UPDATE OR DELETE ON approval_instances
FOR EACH ROW EXECUTE FUNCTION protect_approval_instance_history();

CREATE TRIGGER trg_approval_actions_append_only
BEFORE UPDATE OR DELETE ON approval_actions
FOR EACH ROW EXECUTE FUNCTION deny_update_or_delete();

CREATE OR REPLACE FUNCTION protect_formal_approval_action_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1
    FROM approval_instances approval
    WHERE approval.tenant_id = NEW.tenant_id
      AND approval.id = NEW.approval_instance_id
    FOR UPDATE;

    IF EXISTS (
        SELECT 1
        FROM invoices invoice
        WHERE invoice.tenant_id = NEW.tenant_id
          AND invoice.approval_instance_id = NEW.approval_instance_id
    ) THEN
        RAISE EXCEPTION 'formal invoice approval history is frozen'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION lock_approval_history_for_formal_invoice()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1
    FROM approval_instances approval
    WHERE approval.tenant_id = NEW.tenant_id
      AND approval.id = NEW.approval_instance_id
    FOR UPDATE;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_invoices_lock_approval_history
BEFORE INSERT ON invoices
FOR EACH ROW EXECUTE FUNCTION lock_approval_history_for_formal_invoice();

CREATE TRIGGER trg_approval_actions_protect_formal_invoice
BEFORE INSERT ON approval_actions
FOR EACH ROW EXECUTE FUNCTION protect_formal_approval_action_insert();

CREATE OR REPLACE FUNCTION protect_usage_snapshot_file_link()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_tenant_id uuid;
    target_snapshot_id uuid;
    protected boolean;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        target_tenant_id := OLD.tenant_id;
        target_snapshot_id := OLD.usage_snapshot_id;
        PERFORM 1
        FROM usage_snapshots snapshot
        WHERE snapshot.tenant_id = target_tenant_id
          AND snapshot.id = target_snapshot_id
        FOR UPDATE;

        SELECT snapshot.snapshot_kind = 'FINAL' OR EXISTS (
                   SELECT 1
                   FROM invoice_items item
                   WHERE item.tenant_id = snapshot.tenant_id
                     AND item.usage_snapshot_id = snapshot.id
               )
        INTO protected
        FROM usage_snapshots snapshot
        WHERE snapshot.tenant_id = target_tenant_id
          AND snapshot.id = target_snapshot_id;
        IF COALESCE(protected, false) THEN
            RAISE EXCEPTION 'formal usage evidence links are immutable'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        target_tenant_id := NEW.tenant_id;
        target_snapshot_id := NEW.usage_snapshot_id;
        PERFORM 1
        FROM usage_snapshots snapshot
        WHERE snapshot.tenant_id = target_tenant_id
          AND snapshot.id = target_snapshot_id
        FOR UPDATE;

        SELECT snapshot.snapshot_kind = 'FINAL' OR EXISTS (
                   SELECT 1
                   FROM invoice_items item
                   WHERE item.tenant_id = snapshot.tenant_id
                     AND item.usage_snapshot_id = snapshot.id
               )
        INTO protected
        FROM usage_snapshots snapshot
        WHERE snapshot.tenant_id = target_tenant_id
          AND snapshot.id = target_snapshot_id;
        IF COALESCE(protected, false) THEN
            RAISE EXCEPTION 'formal usage evidence links are immutable'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION lock_usage_evidence_for_formal_item()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.usage_snapshot_id IS NOT NULL THEN
        PERFORM 1
        FROM usage_snapshots snapshot
        WHERE snapshot.tenant_id = NEW.tenant_id
          AND snapshot.id = NEW.usage_snapshot_id
        FOR UPDATE;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_invoice_items_lock_usage_evidence
BEFORE INSERT ON invoice_items
FOR EACH ROW EXECUTE FUNCTION lock_usage_evidence_for_formal_item();

CREATE TRIGGER trg_usage_snapshot_files_protect_formal_evidence
BEFORE INSERT OR UPDATE OR DELETE ON usage_snapshot_files
FOR EACH ROW EXECUTE FUNCTION protect_usage_snapshot_file_link();

CREATE OR REPLACE FUNCTION protect_frozen_file_metadata()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM invoice_files link
        WHERE link.tenant_id = OLD.tenant_id AND link.file_id = OLD.id
    ) OR EXISTS (
        SELECT 1 FROM invoice_adjustments adjustment
        WHERE adjustment.tenant_id = OLD.tenant_id AND adjustment.attachment_file_id = OLD.id
    ) OR EXISTS (
        SELECT 1 FROM payments payment
        WHERE payment.tenant_id = OLD.tenant_id AND payment.attachment_file_id = OLD.id
    ) OR EXISTS (
        SELECT 1
        FROM invoice_template_assets asset
        JOIN invoice_template_versions version
          ON version.tenant_id = asset.tenant_id
         AND version.id = asset.template_version_id
        WHERE asset.tenant_id = OLD.tenant_id
          AND asset.file_id = OLD.id
          AND version.status IN ('PUBLISHED', 'RETIRED')
    ) OR EXISTS (
        SELECT 1
        FROM usage_snapshot_files link
        JOIN usage_snapshots snapshot
          ON snapshot.tenant_id = link.tenant_id
         AND snapshot.id = link.usage_snapshot_id
        WHERE link.tenant_id = OLD.tenant_id
          AND link.file_id = OLD.id
          AND (
              snapshot.snapshot_kind = 'FINAL'
              OR EXISTS (
                  SELECT 1
                  FROM invoice_items item
                  WHERE item.tenant_id = snapshot.tenant_id
                    AND item.usage_snapshot_id = snapshot.id
              )
          )
    ) THEN
        RAISE EXCEPTION 'file metadata referenced by frozen evidence is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_files_protect_frozen_evidence
BEFORE UPDATE OR DELETE ON files
FOR EACH ROW EXECUTE FUNCTION protect_frozen_file_metadata();

CREATE OR REPLACE FUNCTION protect_payment_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    allowed_columns text[] := ARRAY['status', 'updated_at', 'version'];
BEGIN
    IF (to_jsonb(NEW) - allowed_columns) IS DISTINCT FROM (to_jsonb(OLD) - allowed_columns) THEN
        RAISE EXCEPTION 'payment financial and identity fields are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payments_history_immutable';
    END IF;
    IF NEW.version <> OLD.version + 1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'payment status updates require a monotonic version and timestamp'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payments_status_update';
    END IF;
    IF OLD.status = 'VOIDED' AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'terminal payment status cannot change'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payments_status_transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payments_protect_history
BEFORE UPDATE ON payments
FOR EACH ROW EXECUTE FUNCTION protect_payment_update();

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
    IF NEW.reversed_by IS NULL OR NEW.reversed_at IS NULL OR NEW.reversal_reason IS NULL THEN
        RAISE EXCEPTION 'reversed payment allocation requires actor, time, and reason'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_allocations_reversal_state';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_allocations_protect_history
BEFORE UPDATE ON payment_allocations
FOR EACH ROW EXECUTE FUNCTION protect_payment_allocation_update();

CREATE OR REPLACE FUNCTION protect_payment_refund_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') THEN
        RAISE EXCEPTION 'payment refund financial and identity fields are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_refunds_history_immutable';
    END IF;
    IF NOT (
        NEW.status = OLD.status
        OR (OLD.status = 'PENDING' AND NEW.status IN ('CONFIRMED', 'FAILED', 'VOIDED'))
        OR (OLD.status = 'CONFIRMED' AND NEW.status = 'VOIDED')
    ) THEN
        RAISE EXCEPTION 'invalid payment refund status transition: % -> %', OLD.status, NEW.status
            USING ERRCODE = '23514', CONSTRAINT = 'ck_payment_refunds_status_transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_refunds_protect_history
BEFORE UPDATE ON payment_refunds
FOR EACH ROW EXECUTE FUNCTION protect_payment_refund_update();

CREATE OR REPLACE FUNCTION deny_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% history cannot be deleted', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_payments_no_delete
BEFORE DELETE ON payments
FOR EACH ROW EXECUTE FUNCTION deny_delete();

CREATE TRIGGER trg_payment_allocations_no_delete
BEFORE DELETE ON payment_allocations
FOR EACH ROW EXECUTE FUNCTION deny_delete();

CREATE TRIGGER trg_payment_refunds_no_delete
BEFORE DELETE ON payment_refunds
FOR EACH ROW EXECUTE FUNCTION deny_delete();
