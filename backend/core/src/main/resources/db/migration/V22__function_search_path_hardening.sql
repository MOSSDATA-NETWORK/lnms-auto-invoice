-- Trigger functions run with the caller's privileges, but relation lookup must not
-- allow an application session to shadow public tables with pg_temp objects.
-- Listing pg_temp explicitly after public disables PostgreSQL's implicit temp-first lookup.

ALTER FUNCTION public.bump_user_security_version() SECURITY INVOKER;
ALTER FUNCTION public.bump_user_security_version()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.deny_delete() SECURITY INVOKER;
ALTER FUNCTION public.deny_delete()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.deny_update_or_delete() SECURITY INVOKER;
ALTER FUNCTION public.deny_update_or_delete()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.enforce_payment_balance_conservation() SECURITY INVOKER;
ALTER FUNCTION public.enforce_payment_balance_conservation()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.lock_approval_history_for_formal_invoice() SECURITY INVOKER;
ALTER FUNCTION public.lock_approval_history_for_formal_invoice()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.lock_direct_frozen_file_reference() SECURITY INVOKER;
ALTER FUNCTION public.lock_direct_frozen_file_reference()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.lock_frozen_file_row(uuid, uuid) SECURITY INVOKER;
ALTER FUNCTION public.lock_frozen_file_row(uuid, uuid)
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.lock_template_asset_files_for_freeze() SECURITY INVOKER;
ALTER FUNCTION public.lock_template_asset_files_for_freeze()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.lock_usage_evidence_for_formal_item() SECURITY INVOKER;
ALTER FUNCTION public.lock_usage_evidence_for_formal_item()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.lock_usage_files_for_final_transition() SECURITY INVOKER;
ALTER FUNCTION public.lock_usage_files_for_final_transition()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_approval_instance_history() SECURITY INVOKER;
ALTER FUNCTION public.protect_approval_instance_history()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_formal_approval_action_insert() SECURITY INVOKER;
ALTER FUNCTION public.protect_formal_approval_action_insert()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_formal_invoice() SECURITY INVOKER;
ALTER FUNCTION public.protect_formal_invoice()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_frozen_file_metadata() SECURITY INVOKER;
ALTER FUNCTION public.protect_frozen_file_metadata()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_payment_allocation_update() SECURITY INVOKER;
ALTER FUNCTION public.protect_payment_allocation_update()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_payment_insert() SECURITY INVOKER;
ALTER FUNCTION public.protect_payment_insert()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_payment_refund_update() SECURITY INVOKER;
ALTER FUNCTION public.protect_payment_refund_update()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_payment_update() SECURITY INVOKER;
ALTER FUNCTION public.protect_payment_update()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_published_version() SECURITY INVOKER;
ALTER FUNCTION public.protect_published_version()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_published_version_child() SECURITY INVOKER;
ALTER FUNCTION public.protect_published_version_child()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_referenced_usage_snapshot() SECURITY INVOKER;
ALTER FUNCTION public.protect_referenced_usage_snapshot()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.protect_usage_snapshot_file_link() SECURITY INVOKER;
ALTER FUNCTION public.protect_usage_snapshot_file_link()
    SET search_path = pg_catalog, public, pg_temp;

ALTER FUNCTION public.refresh_payment_status_from_history() SECURITY INVOKER;
ALTER FUNCTION public.refresh_payment_status_from_history()
    SET search_path = pg_catalog, public, pg_temp;
