ALTER TABLE tenant ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE user_account ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_tenant_active_lookup ON tenant(status, id);
CREATE INDEX idx_user_account_active_lookup ON user_account(status, id);
