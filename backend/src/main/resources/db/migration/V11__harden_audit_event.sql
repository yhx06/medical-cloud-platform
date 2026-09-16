ALTER TABLE audit_event MODIFY COLUMN request_id VARCHAR(36) NULL;

UPDATE audit_event
SET request_id = NULL
WHERE request_id = 'unknown';

ALTER TABLE audit_event
    ADD CONSTRAINT chk_audit_event_action
        CHECK (action IN (
            'LOGIN_SUCCEEDED', 'LOGIN_REJECTED',
            'TENANT_CREATED', 'TENANT_STATUS_CHANGED', 'HOSPITAL_ADMIN_CREATED',
            'ACCOUNT_STATUS_CHANGED', 'DEPARTMENT_CREATED', 'DEPARTMENT_UPDATED',
            'DOCTOR_CREATED', 'DOCTOR_STATUS_CHANGED', 'SCHEDULE_PUBLISHED',
            'APPOINTMENT_BOOKED', 'APPOINTMENT_CANCELLED', 'CONSULTATION_STARTED',
            'CONSULTATION_DRAFT_SAVED', 'CONSULTATION_COMPLETED', 'RECORD_CORRECTION_APPENDED',
            'ORDER_CREATED', 'ORDER_PAID', 'FILE_UPLOADED', 'FILE_METADATA_ACCESSED',
            'FILE_DOWNLOAD_URL_CREATED'
        ));

ALTER TABLE audit_event
    ADD CONSTRAINT chk_audit_event_object_type
        CHECK (object_type IN (
            'TENANT', 'USER_ACCOUNT', 'DEPARTMENT', 'DOCTOR', 'SCHEDULE_SLOT',
            'APPOINTMENT', 'CONSULTATION', 'MEDICAL_ORDER', 'FILE'
        ));

ALTER TABLE audit_event
    ADD CONSTRAINT chk_audit_event_result
        CHECK (result IN ('SUCCESS', 'REJECTED'));

ALTER TABLE audit_event
    ADD CONSTRAINT chk_audit_event_request_id_shape
        CHECK (
            request_id IS NULL
            OR (
                CHAR_LENGTH(request_id) = 36
                AND SUBSTRING(request_id, 9, 1) = '-'
                AND SUBSTRING(request_id, 14, 1) = '-'
                AND SUBSTRING(request_id, 19, 1) = '-'
                AND SUBSTRING(request_id, 24, 1) = '-'
            )
        );
