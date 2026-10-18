package com.medcloud.course.contract;

public enum AuditEventType {
    LOGIN(AuditObjectType.ACCOUNT),
    TENANT_OPEN(AuditObjectType.TENANT),
    HOSPITAL_ADMIN_CREATE(AuditObjectType.HOSPITAL_ADMIN_ACCOUNT),
    HOSPITAL_ADMIN_STATUS_CHANGE(AuditObjectType.HOSPITAL_ADMIN_ACCOUNT),
    TENANT_STATUS_CHANGE(AuditObjectType.TENANT),
    DEPARTMENT_CREATE(AuditObjectType.DEPARTMENT),
    DEPARTMENT_UPDATE(AuditObjectType.DEPARTMENT),
    DOCTOR_CREATE(AuditObjectType.DOCTOR_ACCOUNT),
    DOCTOR_UPDATE(AuditObjectType.DOCTOR_ACCOUNT),
    SLOT_CREATE(AuditObjectType.SLOT),
    SLOT_UPDATE(AuditObjectType.SLOT),
    APPOINTMENT_BOOK(AuditObjectType.APPOINTMENT),
    APPOINTMENT_CANCEL(AuditObjectType.APPOINTMENT),
    APPOINTMENT_COMPLAINT_UPDATE(AuditObjectType.APPOINTMENT),
    CONSULTATION_DRAFT_SAVE(AuditObjectType.CLINICAL_DRAFT),
    RECORD_CORRECTION_APPEND(AuditObjectType.RECORD_CORRECTION),
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
