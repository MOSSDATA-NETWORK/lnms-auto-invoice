ALTER TABLE users
    ADD COLUMN mfa_last_accepted_counter bigint NOT NULL DEFAULT -1,
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false,
    ADD COLUMN temporary_password_expires_at timestamptz,
    ADD CONSTRAINT chk_users_mfa_last_accepted_counter
        CHECK (mfa_last_accepted_counter >= -1),
    ADD CONSTRAINT chk_users_temporary_password_state
        CHECK (
            (must_change_password AND temporary_password_expires_at IS NOT NULL)
            OR (NOT must_change_password AND temporary_password_expires_at IS NULL)
        );

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tenants
        GROUP BY lower(tenant_code)
        HAVING count(*) > 1
    ) OR EXISTS (
        SELECT 1 FROM users
        GROUP BY tenant_id, lower(username)
        HAVING count(*) > 1
    ) OR EXISTS (
        SELECT 1 FROM users
        GROUP BY tenant_id, lower(email)
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'V17 cannot enforce case-insensitive login uniqueness: duplicate tenant code, username, or email exists';
    END IF;
END;
$$;

CREATE UNIQUE INDEX uq_tenants_tenant_code_ci
    ON tenants(lower(tenant_code));

CREATE UNIQUE INDEX uq_users_tenant_username_ci
    ON users(tenant_id, lower(username));

CREATE UNIQUE INDEX uq_users_tenant_email_ci
    ON users(tenant_id, lower(email));

CREATE TABLE mfa_enrollment_proofs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    proof_hash char(64) NOT NULL UNIQUE,
    session_binding_hash char(64) NOT NULL,
    secret_version bigint NOT NULL CHECK (secret_version >= 0),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_mfa_enrollment_proofs_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_mfa_enrollment_proofs_active_user
    ON mfa_enrollment_proofs(tenant_id, user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

-- V17 switches low-entropy authentication bucket identifiers from raw SHA-256
-- to a master-key-derived HMAC. These rows are transient coordination state and
-- cannot be translated without the original identifiers, so discard legacy buckets.
DELETE FROM authentication_rate_limits;

CREATE INDEX idx_authentication_rate_limits_cleanup
    ON authentication_rate_limits(updated_at);

CREATE INDEX idx_mfa_login_challenges_cleanup
    ON mfa_login_challenges(updated_at);

CREATE INDEX idx_mfa_enrollment_proofs_cleanup
    ON mfa_enrollment_proofs(updated_at);

DROP TRIGGER trg_users_bump_security_version ON users;

CREATE OR REPLACE FUNCTION bump_user_security_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status
        OR NEW.password_hash IS DISTINCT FROM OLD.password_hash
        OR NEW.mfa_enabled IS DISTINCT FROM OLD.mfa_enabled
        OR NEW.must_change_password IS DISTINCT FROM OLD.must_change_password
        OR NEW.temporary_password_expires_at IS DISTINCT FROM OLD.temporary_password_expires_at THEN
        NEW.security_version := OLD.security_version + 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_users_bump_security_version
BEFORE UPDATE OF status, password_hash, mfa_enabled, must_change_password, temporary_password_expires_at ON users
FOR EACH ROW EXECUTE FUNCTION bump_user_security_version();
