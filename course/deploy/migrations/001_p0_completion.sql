-- Apply only after upgrade-p0-database.ps1 validates the complete legacy shape and writes a backup.
USE medical_cloud_course;

ALTER TABLE appointment ADD COLUMN started_at DATETIME NULL;
ALTER TABLE clinical_record
    ADD COLUMN complaint VARCHAR(500) NULL,
    ADD COLUMN notes VARCHAR(2000) NULL,
    ADD COLUMN completed_at DATETIME NULL;
ALTER TABLE payment_order ADD COLUMN paid_at DATETIME NULL;
ALTER TABLE appointment
    ADD INDEX idx_course_patient_slot (patient_id, slot_id),
    DROP INDEX uk_course_patient_slot;
