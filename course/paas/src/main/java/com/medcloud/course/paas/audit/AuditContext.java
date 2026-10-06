package com.medcloud.course.paas.audit;

import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.CurrentActor;
import java.util.UUID;

/** Per-operation scalar snapshot. Only authenticated services may attach identity/ownership. */
public final class AuditContext {
    private final AuditEventType event;
    private final String requestId;
    private String actorId;
    private Role actorRole;
    private String actorTenantId;
    private String tenantId;

    private AuditContext(AuditEventType event, String requestId) {
        if (event == null) throw new IllegalArgumentException("Audit event required");
        this.event = event; this.requestId = requireRequestId(requestId);
    }
    public static AuditContext create(AuditEventType event, String serverRequestId) {
        return new AuditContext(event, serverRequestId);
    }
    public void attachActor(CurrentActor verifiedActor) {
        if (verifiedActor == null || verifiedActor.getRole() == null) throw new IllegalArgumentException("Verified actor required");
        String id = scalarId(verifiedActor.getUserId(), false);
        String hospital = scalarId(verifiedActor.getTenantId(), true);
        Role role = verifiedActor.getRole();
        if ((role == Role.DOCTOR || role == Role.HOSPITAL_ADMIN) && hospital == null)
            throw new IllegalArgumentException("Verified hospital required");
        actorId = id; actorRole = role; actorTenantId = hospital;
        tenantId = role == Role.DOCTOR || role == Role.HOSPITAL_ADMIN ? hospital : null;
    }
    public void attachVerifiedTenant(String tenantId) {
        String hospital = scalarId(tenantId, false);
        if ((actorRole == Role.DOCTOR || actorRole == Role.HOSPITAL_ADMIN) && !hospital.equals(actorTenantId))
            throw new IllegalArgumentException("Hospital outside verified actor scope");
        this.tenantId = hospital;
    }
    public String getRequestId() { return requestId; }
    public AuditEventType getEventType() { return event; }
    public String getActorId() { return actorId; }
    public Role getActorRole() { return actorRole; }
    public String getTenantId() { return tenantId; }
    String failureActorId() { return event == AuditEventType.LOGIN ? null : actorId; }
    Role failureActorRole() { return event == AuditEventType.LOGIN ? null : actorRole; }
    String failureTenantId() {
        if (event == AuditEventType.LOGIN || actorRole == null || actorRole == Role.PLATFORM_ADMIN) return null;
        return actorRole == Role.PATIENT ? tenantId : actorTenantId;
    }
    public static String requireRequestId(String value) {
        try {
            if (value == null || value.length() != 36 || !UUID.fromString(value).toString().equals(value))
                throw new IllegalArgumentException("Server request UUID required");
            return value;
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Server request UUID required"); }
    }
    static String scalarId(String value, boolean nullable) {
        if (value == null && nullable) return null;
        if (value == null || value.trim().isEmpty() || value.length() > 36)
            throw new IllegalArgumentException("Invalid audit scalar ID");
        return value;
    }
}
