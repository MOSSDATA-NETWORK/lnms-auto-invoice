CREATE OR REPLACE FUNCTION public.lock_frozen_file_row(p_tenant_id uuid, p_file_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF p_file_id IS NULL THEN
        RETURN;
    END IF;

    PERFORM 1
    FROM public.files file
    WHERE file.tenant_id = p_tenant_id
      AND file.id = p_file_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'file % does not exist in tenant %', p_file_id, p_tenant_id
            USING ERRCODE = '23503';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.lock_direct_frozen_file_reference()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
DECLARE
    target_file_id uuid;
BEGIN
    target_file_id := (to_jsonb(NEW) ->> TG_ARGV[0])::uuid;
    PERFORM public.lock_frozen_file_row(NEW.tenant_id, target_file_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_invoice_files_lock_file_reference
BEFORE INSERT ON public.invoice_files
FOR EACH ROW EXECUTE FUNCTION public.lock_direct_frozen_file_reference('file_id');

CREATE TRIGGER trg_invoice_adjustments_lock_file_reference
BEFORE INSERT ON public.invoice_adjustments
FOR EACH ROW EXECUTE FUNCTION public.lock_direct_frozen_file_reference('attachment_file_id');

CREATE TRIGGER trg_payments_lock_attachment_file
BEFORE INSERT OR UPDATE OF attachment_file_id ON public.payments
FOR EACH ROW EXECUTE FUNCTION public.lock_direct_frozen_file_reference('attachment_file_id');

-- protect_published_version_child locks the template version first. PostgreSQL runs
-- same-event triggers by name, so the z-prefixed file trigger preserves parent -> file order.
CREATE TRIGGER trg_template_assets_z_lock_file_reference
BEFORE INSERT OR UPDATE ON public.invoice_template_assets
FOR EACH ROW EXECUTE FUNCTION public.lock_direct_frozen_file_reference('file_id');

CREATE OR REPLACE FUNCTION public.lock_template_asset_files_for_freeze()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.status IN ('PUBLISHED', 'RETIRED')
       AND OLD.status NOT IN ('PUBLISHED', 'RETIRED') THEN
        PERFORM file.id
        FROM public.invoice_template_assets asset
        JOIN public.files file
          ON file.tenant_id = asset.tenant_id
         AND file.id = asset.file_id
        WHERE asset.tenant_id = NEW.tenant_id
          AND asset.template_version_id = NEW.id
        ORDER BY file.id
        FOR UPDATE OF file;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_template_versions_lock_asset_files_for_freeze
BEFORE UPDATE OF status ON public.invoice_template_versions
FOR EACH ROW EXECUTE FUNCTION public.lock_template_asset_files_for_freeze();

CREATE OR REPLACE FUNCTION public.protect_usage_snapshot_file_link()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
DECLARE
    old_tenant_id uuid;
    old_snapshot_id uuid;
    old_file_id uuid;
    new_tenant_id uuid;
    new_snapshot_id uuid;
    new_file_id uuid;
    protected boolean;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        old_tenant_id := OLD.tenant_id;
        old_snapshot_id := OLD.usage_snapshot_id;
        old_file_id := OLD.file_id;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_tenant_id := NEW.tenant_id;
        new_snapshot_id := NEW.usage_snapshot_id;
        new_file_id := NEW.file_id;
    END IF;

    -- Every path locks the full parent set before the file set. The stable order
    -- prevents two reverse link moves from taking OLD/NEW locks in opposite order.
    PERFORM snapshot.id
    FROM public.usage_snapshots snapshot
    WHERE (old_snapshot_id IS NOT NULL
           AND snapshot.tenant_id = old_tenant_id
           AND snapshot.id = old_snapshot_id)
       OR (new_snapshot_id IS NOT NULL
           AND snapshot.tenant_id = new_tenant_id
           AND snapshot.id = new_snapshot_id)
    ORDER BY snapshot.id, snapshot.tenant_id
    FOR UPDATE OF snapshot;

    PERFORM file.id
    FROM public.files file
    WHERE (old_file_id IS NOT NULL
           AND file.tenant_id = old_tenant_id
           AND file.id = old_file_id)
       OR (new_file_id IS NOT NULL
           AND file.tenant_id = new_tenant_id
           AND file.id = new_file_id)
    ORDER BY file.id, file.tenant_id
    FOR UPDATE OF file;

    IF old_file_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM public.files file
        WHERE file.tenant_id = old_tenant_id
          AND file.id = old_file_id
    ) THEN
        RAISE EXCEPTION 'file % does not exist in tenant %', old_file_id, old_tenant_id
            USING ERRCODE = '23503';
    END IF;

    IF new_file_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM public.files file
        WHERE file.tenant_id = new_tenant_id
          AND file.id = new_file_id
    ) THEN
        RAISE EXCEPTION 'file % does not exist in tenant %', new_file_id, new_tenant_id
            USING ERRCODE = '23503';
    END IF;

    IF old_snapshot_id IS NOT NULL THEN
        SELECT snapshot.snapshot_kind = 'FINAL' OR EXISTS (
                   SELECT 1
                   FROM public.invoice_items item
                   WHERE item.tenant_id = snapshot.tenant_id
                     AND item.usage_snapshot_id = snapshot.id
               )
        INTO protected
        FROM public.usage_snapshots snapshot
        WHERE snapshot.tenant_id = old_tenant_id
          AND snapshot.id = old_snapshot_id;
        IF COALESCE(protected, false) THEN
            RAISE EXCEPTION 'formal usage evidence links are immutable'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF new_snapshot_id IS NOT NULL THEN
        SELECT snapshot.snapshot_kind = 'FINAL' OR EXISTS (
                   SELECT 1
                   FROM public.invoice_items item
                   WHERE item.tenant_id = snapshot.tenant_id
                     AND item.usage_snapshot_id = snapshot.id
               )
        INTO protected
        FROM public.usage_snapshots snapshot
        WHERE snapshot.tenant_id = new_tenant_id
          AND snapshot.id = new_snapshot_id;
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

CREATE OR REPLACE FUNCTION public.lock_usage_evidence_for_formal_item()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.usage_snapshot_id IS NOT NULL THEN
        PERFORM 1
        FROM public.usage_snapshots snapshot
        WHERE snapshot.tenant_id = NEW.tenant_id
          AND snapshot.id = NEW.usage_snapshot_id
        FOR UPDATE;

        PERFORM file.id
        FROM public.usage_snapshot_files link
        JOIN public.files file
          ON file.tenant_id = link.tenant_id
         AND file.id = link.file_id
        WHERE link.tenant_id = NEW.tenant_id
          AND link.usage_snapshot_id = NEW.usage_snapshot_id
        ORDER BY file.id
        FOR UPDATE OF file;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.lock_usage_files_for_final_transition()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.snapshot_kind = 'FINAL' AND OLD.snapshot_kind <> 'FINAL' THEN
        PERFORM file.id
        FROM public.usage_snapshot_files link
        JOIN public.files file
          ON file.tenant_id = link.tenant_id
         AND file.id = link.file_id
        WHERE link.tenant_id = NEW.tenant_id
          AND link.usage_snapshot_id = NEW.id
        ORDER BY file.id
        FOR UPDATE OF file;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_usage_snapshots_lock_files_for_final
BEFORE UPDATE OF snapshot_kind ON public.usage_snapshots
FOR EACH ROW EXECUTE FUNCTION public.lock_usage_files_for_final_transition();
