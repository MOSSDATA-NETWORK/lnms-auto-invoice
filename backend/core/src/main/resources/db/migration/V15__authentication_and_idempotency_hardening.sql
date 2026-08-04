ALTER TABLE users
    ADD COLUMN security_version bigint NOT NULL DEFAULT 1;

CREATE OR REPLACE FUNCTION bump_user_security_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status
        OR NEW.password_hash IS DISTINCT FROM OLD.password_hash
        OR NEW.mfa_enabled IS DISTINCT FROM OLD.mfa_enabled THEN
        NEW.security_version := OLD.security_version + 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_users_bump_security_version
BEFORE UPDATE OF status, password_hash, mfa_enabled ON users
FOR EACH ROW EXECUTE FUNCTION bump_user_security_version();

-- Existing rows cannot be safely attributed to an actor. Idempotency records are
-- short-lived coordination data, so invalidate them during the maintenance migration
-- instead of permitting an ambiguous cross-user replay.
DELETE FROM idempotency_keys;

ALTER TABLE idempotency_keys
    DROP CONSTRAINT IF EXISTS idempotency_keys_tenant_id_idempotency_key_key,
    ADD COLUMN actor_id uuid NOT NULL,
    ADD CONSTRAINT fk_idempotency_keys_actor
        FOREIGN KEY (tenant_id, actor_id) REFERENCES users(tenant_id, id),
    ADD CONSTRAINT uq_idempotency_keys_actor
        UNIQUE (tenant_id, actor_id, idempotency_key);

CREATE TABLE authentication_rate_limits (
    bucket_type varchar(32) NOT NULL
        CHECK (bucket_type IN ('LOGIN_IDENTITY', 'LOGIN_IP', 'MFA_USER', 'MFA_IP')),
    bucket_key_hash char(64) NOT NULL,
    tenant_id uuid REFERENCES tenants(id) ON DELETE CASCADE,
    user_id uuid,
    failure_count integer NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    window_started_at timestamptz NOT NULL,
    blocked_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket_type, bucket_key_hash),
    CONSTRAINT fk_authentication_rate_limits_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_authentication_rate_limits_blocked
    ON authentication_rate_limits(blocked_until)
    WHERE blocked_until IS NOT NULL;

CREATE TABLE mfa_login_challenges (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    session_binding_hash char(64) NOT NULL,
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_mfa_login_challenges_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES users(tenant_id, id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_mfa_login_challenges_active_user
    ON mfa_login_challenges(tenant_id, user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX idx_mfa_login_challenges_expiry
    ON mfa_login_challenges(expires_at)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
