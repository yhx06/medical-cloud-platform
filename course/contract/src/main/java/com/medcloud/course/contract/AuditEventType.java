package com.medcloud.course.contract;

public enum AuditEventType {
    LOGIN(AuditObjectType.ACCOUNT),
    TENANT_STATUS_CHANGE(AuditObjectType.TENANT),
    DEPARTMENT_CREATE(AuditObjectType.DEPARTMENT),
    DEPARTMENT_UPDATE(AuditObjectType.DEPARTMENT),
    DOCTOR_CREATE(AuditObjectType.DOCTOR_ACCOUNT),
    DOCTOR_UPDATE(AuditObjectType.DOCTOR_ACCOUNT),
    SLOT_CREATE(AuditObjectType.SLOT),
    SLOT_UPDATE(AuditObjectType.SLOT),
    APPOINTMENT_BOOK(AuditObjectType.APPOINTMENT),
    APPOINTMENT_CANCEL(AuditObjectType.APPOINTMENT),
    CONSULTATION_START(AuditObjectType.APPOINTMENT),
    CONSULTATION_COMPLETE(AuditObjectType.CLINICAL_RECORD),
    PAYMENT_PAY(AuditObjectType.PAYMENT_ORDER),
    FILE_UPLOAD(AuditObjectType.FILE_METADATA),
    FILE_DELETE(AuditObjectType.FILE_METADATA),
    FILE_DOWNLOAD_AUTHORIZE(AuditObjectType.FILE_METADATA);

    private final AuditObjectType objectType;
    AuditEventType(AuditObjectType objectType) { this.objectType = objectType; }
    public AuditObjectType getObjectType() { return objectType; }
}
