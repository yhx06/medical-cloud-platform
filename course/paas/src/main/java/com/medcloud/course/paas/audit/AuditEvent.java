package com.medcloud.course.paas.audit;

import com.medcloud.course.contract.*;
import javax.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Append-only scalar snapshot: no business relationships or free-form payload. */
@Entity
@Table(name = "audit_event", uniqueConstraints = @UniqueConstraint(name = "uk_audit_request_event", columnNames = {"request_id", "event_type"}), indexes = {
    @Index(name = "idx_audit_recorded", columnList = "recorded_at, id"),
    @Index(name = "idx_audit_tenant", columnList = "tenant_id, recorded_at, id"),
    @Index(name = "idx_audit_event_result", columnList = "event_type, result, recorded_at, id")
})
public class AuditEvent {
    @Id @Column(length = 36, updatable = false) private String id;
    @Column(name = "request_id", nullable = false, length = 36, updatable = false) private String requestId;
    @Column(name = "actor_id", length = 36, updatable = false) private String actorId;
    @Enumerated(EnumType.STRING) @Column(name = "actor_role", length = 32, updatable = false) private Role actorRole;
    @Column(name = "tenant_id", length = 36, updatable = false) private String tenantId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 64, updatable = false) private AuditEventType eventType;
    @Enumerated(EnumType.STRING) @Column(name = "object_type", nullable = false, length = 32, updatable = false) private AuditObjectType objectType;
    @Column(name = "object_id", length = 36, updatable = false) private String objectId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16, updatable = false) private AuditResult result;
    @Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false, length = 32, updatable = false) private AuditReasonCode reasonCode;
    // Course datasource and JVM use Asia/Shanghai, as for existing business timestamps.
    @Column(name = "recorded_at", nullable = false, columnDefinition = "DATETIME(6)", updatable = false) private Timestamp recordedAt;

    protected AuditEvent() {}
    private AuditEvent(AuditContext context, String objectId, AuditResult result, AuditReasonCode reason, Instant at) {
        if (context == null || at == null) throw new IllegalArgumentException("Audit context and time required");
        id = UUID.randomUUID().toString(); requestId = context.getRequestId();
        eventType = context.getEventType(); objectType = eventType.getObjectType();
        actorId = result == AuditResult.FAILED ? context.failureActorId() : context.getActorId();
        actorRole = result == AuditResult.FAILED ? context.failureActorRole() : context.getActorRole();
        tenantId = result == AuditResult.FAILED ? context.failureTenantId() : context.getTenantId();
        this.objectId = objectId; this.result = result; reasonCode = reason;
        recordedAt = Timestamp.from(at);
        recordedAt.setNanos((recordedAt.getNanos() / 1000) * 1000);
    }
    static AuditEvent success(AuditContext context, String objectId, AuditResult result, Instant at) {
        if (result != AuditResult.SUCCESS && result != AuditResult.NO_CHANGE) throw new IllegalArgumentException("Successful audit result required");
        return new AuditEvent(context, AuditContext.scalarId(objectId, false), result,
                result == AuditResult.SUCCESS ? AuditReasonCode.OK : AuditReasonCode.UNCHANGED, at);
    }
    static AuditEvent failure(AuditContext context, AuditReasonCode reason, Instant at) {
        if (reason == null || reason == AuditReasonCode.OK || reason == AuditReasonCode.UNCHANGED)
            throw new IllegalArgumentException("Failure reason required");
        return new AuditEvent(context, null, AuditResult.FAILED, reason, at);
    }
    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getActorId() { return actorId; }
    public Role getActorRole() { return actorRole; }
    public String getTenantId() { return tenantId; }
    public AuditEventType getEventType() { return eventType; }
    public AuditObjectType getObjectType() { return objectType; }
    public String getObjectId() { return objectId; }
    public AuditResult getResult() { return result; }
    public AuditReasonCode getReasonCode() { return reasonCode; }
    public Timestamp getRecordedAt() { return recordedAt == null ? null : Timestamp.from(recordedAt.toInstant()); }
}
