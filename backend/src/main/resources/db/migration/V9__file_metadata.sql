CREATE TABLE file_metadata (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    record_id VARCHAR(36) NOT NULL,
    uploader_id VARCHAR(36) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    media_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_file_metadata_size CHECK (size_bytes >= 0 AND size_bytes <= 10485760),
    CONSTRAINT uk_file_metadata_object_key UNIQUE (object_key),
    CONSTRAINT fk_file_metadata_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_file_metadata_record FOREIGN KEY (record_id) REFERENCES consultation(id),
    CONSTRAINT fk_file_metadata_uploader FOREIGN KEY (uploader_id) REFERENCES user_account(id)
);

CREATE INDEX idx_file_metadata_record
    ON file_metadata(record_id, created_at);
CREATE INDEX idx_file_metadata_tenant
    ON file_metadata(tenant_id, created_at);
