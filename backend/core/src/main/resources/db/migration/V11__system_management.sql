ALTER TABLE roles
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_users_status
    ON users(tenant_id, status, username);

CREATE INDEX IF NOT EXISTS idx_user_roles_user
    ON user_roles(tenant_id, user_id, role_id);
