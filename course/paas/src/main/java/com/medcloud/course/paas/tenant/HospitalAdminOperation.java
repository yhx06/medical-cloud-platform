package com.medcloud.course.paas.tenant;

import javax.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Immutable non-secret creation result; never exposed as an HTTP entity. */
@Entity
@Table(name = "hospital_admin_operation", uniqueConstraints = {
    @UniqueConstraint(name = "uk_hospital_admin_operation_actor_key", columnNames = {"actor_id", "operation_key"}),
    @UniqueConstraint(name = "uk_hospital_admin_operation_admin", columnNames = "admin_id")
}, indexes = @Index(name = "idx_hospital_admin_operation_tenant", columnList = "tenant_id"))
public class HospitalAdminOperation {
    @Id @Column(length = 36, updatable = false) private String id;
    @Column(name = "actor_id", nullable = false, length = 36, updatable = false) private String actorId;
    @Column(name = "operation_key", nullable = false, length = 36, updatable = false) private String operationKey;
    @Column(name = "operation_type", nullable = false, length = 32, updatable = false) private String operationType;
    @Column(name = "tenant_id", nullable = false, length = 36, updatable = false) private String tenantId;
    @Column(name = "admin_id", nullable = false, length = 36, updatable = false) private String adminId;
    @Column(name = "hospital_name", nullable = false, length = 100, updatable = false) private String hospitalName;
    @Column(name = "admin_username", nullable = false, length = 64, updatable = false) private String adminUsername;
    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)", updatable = false) private Timestamp createdAt;
    protected HospitalAdminOperation() { }
    HospitalAdminOperation(String actorId, String key, String type, Tenant tenant,
                           com.medcloud.course.paas.auth.Account administrator, Instant at) {
        id = UUID.randomUUID().toString(); this.actorId = actorId; operationKey = key; operationType = type;
        tenantId = tenant.getId(); adminId = administrator.getId(); hospitalName = tenant.getName();
        adminUsername = administrator.getUsername(); createdAt = Timestamp.from(at);
        createdAt.setNanos((createdAt.getNanos() / 1000) * 1000);
    }
    public String getId() { return id; }
    public String getActorId() { return actorId; }
    public String getOperationKey() { return operationKey; }
    public String getOperationType() { return operationType; }
    public String getTenantId() { return tenantId; }
    public String getAdminId() { return adminId; }
    public String getHospitalName() { return hospitalName; }
    public String getAdminUsername() { return adminUsername; }
    public Timestamp getCreatedAt() { return createdAt == null ? null : Timestamp.from(createdAt.toInstant()); }
}
