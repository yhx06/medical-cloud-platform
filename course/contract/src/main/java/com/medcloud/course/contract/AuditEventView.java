package com.medcloud.course.contract;

/** Explicit public audit projection, without private actor or business object identifiers. */
public final class AuditEventView {
    private final String id, requestId, tenantId, tenantName, actorLabel, recordedAt;
    private final Role actorRole;
    private final AuditEventType eventType;
    private final AuditObjectType objectType;
    private final AuditResult result;
    private final AuditReasonCode reasonCode;
    public AuditEventView(String id, String requestId, String tenantId, String tenantName, Role actorRole,
            AuditEventType eventType, AuditObjectType objectType, AuditResult result, AuditReasonCode reasonCode, String recordedAt) {
        this.id=id; this.requestId=requestId; this.tenantId=tenantId; this.tenantName=tenantName;
        this.actorRole=actorRole; this.eventType=eventType; this.objectType=objectType;
        this.result=result; this.reasonCode=reasonCode; this.recordedAt=recordedAt;
        if (actorRole == null) actorLabel="未认证";
        else switch (actorRole) {
            case PATIENT: actorLabel="患者（已脱敏）"; break;
            case DOCTOR: actorLabel="医生（已脱敏）"; break;
            case HOSPITAL_ADMIN: actorLabel="医院管理员（已脱敏）"; break;
            case PLATFORM_ADMIN: actorLabel="平台管理员（已脱敏）"; break;
            default: throw new IllegalArgumentException("unknown actor role");
        }
    }
    public String getId(){return id;}
    public String getRequestId(){return requestId;}
    public String getTenantId(){return tenantId;}
    public String getTenantName(){return tenantName;}
    public Role getActorRole(){return actorRole;}
    public String getActorLabel(){return actorLabel;}
    public AuditEventType getEventType(){return eventType;}
    public AuditObjectType getObjectType(){return objectType;}
    public AuditResult getResult(){return result;}
    public AuditReasonCode getReasonCode(){return reasonCode;}
    public String getRecordedAt(){return recordedAt;}
}
