package com.medcloud.platform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An audit record intentionally contains only enumerated actions and stable identifiers.
 * Request payloads, credentials, clinical text, prescription content, filenames and URLs
 * are deliberately not represented in this model.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "actor_id")
    private UUID actorId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 32)
    private AuditObjectType objectType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "object_id")
    private UUID objectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditResult result;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_id", length = 36)
    private UUID requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    private AuditEvent(
            UUID actorId,
            UUID tenantId,
            AuditAction action,
            AuditObjectType objectType,
            UUID objectId,
            AuditResult result,
            UUID requestId
    ) {
        this.id = UUID.randomUUID();
        this.actorId = actorId;
        this.tenantId = tenantId;
        this.action = action;
        this.objectType = objectType;
        this.objectId = objectId;
        this.result = result;
        this.requestId = requestId;
        this.createdAt = Instant.now();
    }

    public static AuditEvent create(
            UUID actorId,
            UUID tenantId,
            AuditAction action,
            AuditObjectType objectType,
            UUID objectId,
            AuditResult result,
            UUID requestId
    ) {
        return new AuditEvent(
                actorId,
                tenantId,
                Objects.requireNonNull(action, "action is required"),
                Objects.requireNonNull(objectType, "object type is required"),
                objectId,
                Objects.requireNonNull(result, "result is required"),
                requestId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditObjectType getObjectType() {
        return objectType;
    }

    public UUID getObjectId() {
        return objectId;
    }

    public AuditResult getResult() {
        return result;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
