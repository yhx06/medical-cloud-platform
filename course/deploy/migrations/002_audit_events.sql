-- Add-only migration: no historical events and no business-row changes.
CREATE TABLE audit_event (
    id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NULL,
    actor_role VARCHAR(32) NULL,
    tenant_id VARCHAR(36) NULL,
    event_type VARCHAR(64) NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id VARCHAR(36) NULL,
    result VARCHAR(16) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_audit_request_event UNIQUE (request_id, event_type),
    INDEX idx_audit_recorded (recorded_at, id),
    INDEX idx_audit_tenant (tenant_id, recorded_at, id),
    INDEX idx_audit_event_result (event_type, result, recorded_at, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
