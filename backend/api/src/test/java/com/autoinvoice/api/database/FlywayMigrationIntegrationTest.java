package com.autoinvoice.api.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    @Test
    void migratesEmptyPostgresAndCreatesOperationalConstraints() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.migrate().success).isTrue();

        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("""
                        SELECT version FROM flyway_schema_history
                        WHERE success AND version IS NOT NULL
                        ORDER BY installed_rank DESC LIMIT 1
                        """)
                .query(String.class).single()).isEqualTo("34");
        assertThat(jdbc.sql("SELECT to_regclass('tenant_operational_settings') IS NOT NULL")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = 'pricing_rules'
                              AND column_name = 'current_version_id'
                        )
                        """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uq_invoice_single_replacement'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT to_regclass('authentication_rate_limits') IS NOT NULL")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT to_regclass('mfa_login_challenges') IS NOT NULL")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT to_regclass('mfa_enrollment_proofs') IS NOT NULL")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'users'
                          AND column_name IN (
                              'mfa_last_accepted_counter',
                              'must_change_password',
                              'temporary_password_expires_at'
                          )
                        """).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_indexes
                        WHERE indexname IN (
                            'idx_authentication_rate_limits_cleanup',
                            'idx_mfa_login_challenges_cleanup',
                            'idx_mfa_enrollment_proofs_cleanup'
                        )
                        """).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_indexes
                        WHERE indexname IN (
                            'uq_tenants_tenant_code_ci',
                            'uq_users_tenant_username_ci',
                            'uq_users_tenant_email_ci'
                        )
                        """).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT to_regclass('audit_chain_heads') IS NOT NULL")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = 'notification_logs'
                              AND column_name = 'send_started_at'
                        )
                        """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT is_nullable = 'NO'
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'idempotency_keys'
                          AND column_name = 'actor_id'
                        """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_trigger
                        WHERE tgname IN (
                            'trg_payment_amount_balance_guard',
                            'trg_payment_allocations_balance_guard',
                            'trg_payment_refunds_balance_guard'
                        ) AND NOT tgisinternal
                        """).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM pg_constraint
                        WHERE conname IN (
                            'ck_pricing_version_unit_price_nonneg',
                            'ck_pricing_version_base_fee_nonneg',
                            'ck_pricing_version_committed_qty_nonneg',
                            'ck_pricing_version_overage_price_nonneg',
                            'ck_pricing_version_min_charge_nonneg',
                            'ck_pricing_version_max_charge_nonneg',
                            'ck_pricing_version_rounding_mode',
                            'ck_pricing_version_rounding_scale',
                            'ck_pricing_version_decimal_scale_required',
                            'ck_contract_items_default_quantity_nonneg',
                            'ck_pricing_tiers_lower_bound_nonneg',
                            'ck_pricing_tiers_unit_price_nonneg'
                        ) AND convalidated
                        """).query(Integer.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM pg_trigger
                        WHERE tgname IN (
                            'trg_pricing_tiers_protect_published_version',
                            'trg_template_assets_protect_published_version',
                            'trg_approval_steps_protect_published_version',
                             'trg_approval_instances_protect_history',
                             'trg_approval_actions_append_only',
                             'trg_approval_actions_protect_formal_invoice',
                             'trg_invoices_lock_approval_history',
                             'trg_invoice_items_lock_usage_evidence',
                             'trg_usage_snapshot_files_protect_formal_evidence',
                             'trg_files_protect_frozen_evidence',
                             'trg_payments_protect_history',
                             'trg_payment_allocations_protect_history',
                             'trg_payment_refunds_protect_history',
                             'trg_payments_no_delete',
                             'trg_payment_allocations_no_delete',
                             'trg_payment_refunds_no_delete',
                             'trg_preview_items_protect_final_snapshot',
                             'trg_preview_adjustments_protect_final_snapshot',
                             'trg_preview_exclusions_protect_final_snapshot',
                             'trg_invoice_items_check_preview_snapshot',
                             'trg_invoice_adjustments_check_preview_snapshot',
                             'trg_invoices_validate_confirmation_and_replacement',
                             'trg_invoice_relations_validate_replaces',
                             'trg_invoices_require_completed_correction'
                         ) AND NOT tgisinternal
                        """).query(Integer.class).single()).isEqualTo(24);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_trigger
                        WHERE tgname IN (
                            'trg_invoice_files_lock_file_reference',
                            'trg_invoice_adjustments_lock_file_reference',
                            'trg_payments_lock_attachment_file',
                            'trg_template_assets_z_lock_file_reference',
                            'trg_template_versions_lock_asset_files_for_freeze',
                            'trg_usage_snapshots_lock_files_for_final'
                        ) AND NOT tgisinternal
                        """).query(Integer.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM pg_proc procedure
                        JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                        WHERE namespace.nspname = 'public'
                          AND procedure.proname IN (
                              'bump_user_security_version',
                              'deny_delete',
                              'deny_update_or_delete',
                              'enforce_payment_balance_conservation',
                              'lock_approval_history_for_formal_invoice',
                              'lock_direct_frozen_file_reference',
                              'lock_frozen_file_row',
                              'lock_template_asset_files_for_freeze',
                              'lock_usage_evidence_for_formal_item',
                              'lock_usage_files_for_final_transition',
                              'protect_approval_instance_history',
                              'protect_formal_approval_action_insert',
                              'protect_formal_invoice',
                              'protect_frozen_file_metadata',
                              'protect_payment_allocation_update',
                              'protect_payment_insert',
                              'protect_payment_refund_update',
                               'protect_payment_update',
                               'protect_finalizing_preview_child',
                               'protect_published_version',
                               'protect_published_version_child',
                               'protect_referenced_usage_snapshot',
                               'protect_usage_snapshot_file_link',
                               'refresh_payment_status_from_history',
                               'require_completed_correction_replacement',
                               'validate_formal_invoice_adjustment_insert',
                               'validate_formal_invoice_item_insert',
                               'validate_invoice_confirmation_and_replacement',
                               'validate_replaces_relation_insert'
                           )
                          AND NOT procedure.prosecdef
                          AND ARRAY['search_path=pg_catalog, public, pg_temp']::text[]
                              <@ procedure.proconfig
                        """).query(Integer.class).single()).isEqualTo(29);
        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND ((table_name = 'invoice_items'
                                AND column_name = 'source_preview_item_id')
                               OR (table_name = 'invoice_adjustments'
                                   AND column_name = 'source_preview_adjustment_id'))
                          AND is_nullable = 'NO'
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM pg_constraint
                        WHERE conname IN (
                            'uq_invoice_items_source_preview',
                            'uq_invoice_adjustments_source_preview'
                        ) AND contype = 'u'
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_trigger
                        WHERE tgname IN (
                            'trg_payments_protect_initial_state',
                            'trg_payment_allocations_protect_initial_state',
                            'trg_payment_allocations_refresh_payment_status',
                            'trg_payment_refunds_refresh_payment_status'
                        ) AND NOT tgisinternal
                        """).query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                            'ck_payment_allocations_reversal_state',
                            'ck_payments_status_derived'
                        ) AND convalidated
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_constraint
                        WHERE contype = 'f' AND convalidated
                          AND conname LIKE 'fk\\_tenant\\_%' ESCAPE '\\'
                        """).query(Integer.class).single()).isEqualTo(130);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_constraint
                        WHERE contype = 'u'
                          AND conname LIKE 'uq\\_tenant\\_%' ESCAPE '\\'
                        """).query(Integer.class).single()).isEqualTo(30);
        assertThat(jdbc.sql("""
                        SELECT convalidated FROM pg_constraint
                        WHERE conname = 'ck_authentication_rate_limits_identity_scope'
                        """).query(Boolean.class).single()).isTrue();

        UUID tenantId = UUID.randomUUID();
        UUID firstUserId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, 'migration-test', 'Migration test')")
                .param("id", tenantId).update();
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO tenants(id, tenant_code, tenant_name)
                        VALUES (:id, 'MIGRATION-TEST', 'Duplicate migration test')
                        """).param("id", UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name, status)
                        VALUES
                            (:firstId, :tenantId, 'first-user', 'first@example.invalid', 'First user', 'ACTIVE'),
                            (:secondId, :tenantId, 'second-user', 'second@example.invalid', 'Second user', 'ACTIVE')
                        """)
                .param("firstId", firstUserId).param("secondId", secondUserId).param("tenantId", tenantId)
                .update();
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name, status)
                        VALUES (:id, :tenantId, 'FIRST-USER', 'unique@example.invalid', 'Duplicate user', 'ACTIVE')
                        """).param("id", UUID.randomUUID()).param("tenantId", tenantId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("UPDATE users SET status = 'LOCKED' WHERE id = :id")
                .param("id", firstUserId).update();
        assertThat(jdbc.sql("SELECT security_version FROM users WHERE id = :id")
                .param("id", firstUserId).query(Long.class).single()).isEqualTo(2);
        jdbc.sql("""
                        UPDATE users
                        SET must_change_password = true,
                            temporary_password_expires_at = now() + interval '24 hours'
                        WHERE id = :id
                        """).param("id", firstUserId).update();
        assertThat(jdbc.sql("SELECT security_version FROM users WHERE id = :id")
                .param("id", firstUserId).query(Long.class).single()).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE users
                        SET must_change_password = false
                        WHERE id = :id
                        """).param("id", firstUserId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        for (UUID actorId : java.util.List.of(firstUserId, secondUserId)) {
            jdbc.sql("""
                            INSERT INTO idempotency_keys(
                                id, tenant_id, actor_id, idempotency_key, http_method,
                                request_path, request_hash, state, expires_at
                            ) VALUES (
                                :id, :tenantId, :actorId, 'shared-key', 'POST',
                                '/test', repeat('0', 64), 'COMPLETED', now() + interval '1 hour'
                            )
                            """)
                    .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("actorId", actorId)
                    .update();
        }
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM idempotency_keys
                        WHERE tenant_id = :tenantId AND idempotency_key = 'shared-key'
                        """).param("tenantId", tenantId).query(Integer.class).single()).isEqualTo(2);
        jdbc.sql("""
                        INSERT INTO customers(id, tenant_id, customer_no, customer_name, default_currency)
                        VALUES (:id, :tenantId, 'CUST-MIGRATION', 'Migration customer', 'CNY')
                        """)
                .param("id", customerId).param("tenantId", tenantId).update();
        jdbc.sql("""
                        INSERT INTO payments(
                            id, tenant_id, payment_number, customer_id, currency_code,
                            amount_minor, payment_method, source_system, paid_at, status
                        ) VALUES (
                            :id, :tenantId, 'PAY-MIGRATION', :customerId, 'CNY',
                            100, 'BANK_TRANSFER', 'TEST', now(), 'CONFIRMED'
                        )
                        """)
                .param("id", paymentId).param("tenantId", tenantId).param("customerId", customerId).update();

        UUID pricingRuleId = UUID.randomUUID();
        UUID pricingVersionId = UUID.randomUUID();
        UUID pricingTierId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO pricing_rules(id, tenant_id, rule_code, rule_name)
                        VALUES (:id, :tenantId, 'RULE-MIGRATION', 'Migration pricing rule')
                        """)
                .param("id", pricingRuleId).param("tenantId", tenantId).update();
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO pricing_rule_versions(
                            id, tenant_id, pricing_rule_id, version_no, effective_from,
                            billing_type, currency_code, base_fee, rounding_mode,
                            config_json, created_by
                        ) VALUES (
                            :id, :tenantId, :ruleId, 1, now(),
                            'FIXED_MONTHLY', 'CNY', -0.01, 'NONE', '{}'::jsonb, :createdBy
                        )
                        """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId)
                .param("ruleId", pricingRuleId).param("createdBy", firstUserId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("""
                        INSERT INTO pricing_rule_versions(
                            id, tenant_id, pricing_rule_id, version_no, effective_from,
                            billing_type, currency_code, unit_price, rounding_mode,
                            config_json, status, created_by
                        ) VALUES (
                            :id, :tenantId, :ruleId, 1, now(),
                            'QUANTITY_UNIT', 'CNY', 1, 'NONE', '{}'::jsonb, 'DRAFT', :createdBy
                        )
                        """)
                .param("id", pricingVersionId).param("tenantId", tenantId)
                .param("ruleId", pricingRuleId).param("createdBy", firstUserId).update();
        jdbc.sql("""
                        INSERT INTO pricing_tiers(
                            id, tenant_id, pricing_rule_version_id, tier_no,
                            lower_bound, unit_price, pricing_mode
                        ) VALUES (:id, :tenantId, :versionId, 1, 0, 1, 'VOLUME')
                        """)
                .param("id", pricingTierId).param("tenantId", tenantId)
                .param("versionId", pricingVersionId).update();
        jdbc.sql("""
                        UPDATE pricing_rule_versions
                        SET status = 'PUBLISHED', published_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", pricingVersionId).update();
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE pricing_tiers SET unit_price = 2
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", pricingTierId).update())
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO payment_refunds(
                            id, tenant_id, payment_id, refund_number, amount_minor, reason, refunded_at
                        ) VALUES (
                            :id, :tenantId, :paymentId, 'REF-TOO-LARGE', 101, 'guard test', now()
                        )
                        """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("paymentId", paymentId)
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.sql("""
                        INSERT INTO payment_refunds(
                            id, tenant_id, payment_id, refund_number, amount_minor, reason, refunded_at
                        ) VALUES (
                            :id, :tenantId, :paymentId, 'REF-VALID', 40, 'guard test', now()
                        )
                        """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("paymentId", paymentId)
                .update();
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE payments SET amount_minor = 39
                        WHERE tenant_id = :tenantId AND id = :paymentId
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        DELETE FROM payments
                        WHERE tenant_id = :tenantId AND id = :paymentId
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId).update())
                .isInstanceOf(DataAccessException.class);
    }
}
