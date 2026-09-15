package com.medcloud.platform.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_metadata")
public class FileMetadata {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "record_id", nullable = false, updatable = false)
    private UUID recordId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "uploader_id", nullable = false, updatable = false)
    private UUID uploaderId;

    @Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    private String originalFilename;

    @Column(name = "object_key", nullable = false, unique = true, length = 255, updatable = false)
    private String objectKey;

    @Column(name = "media_type", nullable = false, length = 64, updatable = false)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FileMetadata() {
    }

    private FileMetadata(
            UUID tenantId,
            UUID recordId,
            UUID uploaderId,
            String originalFilename,
            String objectKey,
            String mediaType,
            long sizeBytes
    ) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.recordId = recordId;
        this.uploaderId = uploaderId;
        this.originalFilename = originalFilename;
        this.objectKey = objectKey;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.createdAt = Instant.now();
    }

    public static FileMetadata create(
            UUID tenantId,
            UUID recordId,
            UUID uploaderId,
            String originalFilename,
            String objectKey,
            String mediaType,
            long sizeBytes
    ) {
        return new FileMetadata(
                tenantId,
                recordId,
                uploaderId,
                originalFilename,
                objectKey,
                mediaType,
                sizeBytes);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getRecordId() {
        return recordId;
    }

    public UUID getUploaderId() {
        return uploaderId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
