-- Apply only through the protected 005 upgrader to an exact 004 baseline.
-- No historical accounts, hashes or clinical rows are changed or backfilled.
CREATE TABLE hospital_admin_operation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    actor_id VARCHAR(36) NOT NULL,
    operation_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    admin_id VARCHAR(36) NOT NULL,
    hospital_name VARCHAR(100) NOT NULL,
    admin_username VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_hospital_admin_operation_actor_key UNIQUE (actor_id, operation_key),
    CONSTRAINT uk_hospital_admin_operation_admin UNIQUE (admin_id),
    INDEX idx_hospital_admin_operation_tenant (tenant_id),
    CONSTRAINT fk_hospital_admin_operation_actor FOREIGN KEY (actor_id) REFERENCES user_account(id),
    CONSTRAINT fk_hospital_admin_operation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_hospital_admin_operation_admin FOREIGN KEY (admin_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
