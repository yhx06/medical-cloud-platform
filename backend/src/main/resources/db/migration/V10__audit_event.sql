CREATE TABLE audit_event (
    id VARCHAR(36) PRIMARY KEY,
    actor_id VARCHAR(36) NULL,
    tenant_id VARCHAR(36) NULL,
    action VARCHAR(64) NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id VARCHAR(36) NULL,
    result VARCHAR(16) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_event_tenant_created
    ON audit_event(tenant_id, created_at);
CREATE INDEX idx_audit_event_actor_created
    ON audit_event(actor_id, created_at);
CREATE INDEX idx_audit_event_object
    ON audit_event(object_type, object_id);
