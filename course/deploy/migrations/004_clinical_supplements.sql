-- Run only through upgrade-clinical-database.ps1 after the exact 003 baseline.
-- Deployment order: 001 -> 002 -> 003 -> 004. After 004, the strict old
-- upgrade-attachments-database.ps1 (003) refuses the new appointment shape.
-- Use the latest clinical upgrader for read-only verification; do not weaken 003.
-- No transaction can undo MySQL DDL: retain the successful full backup and any
-- partial structure for inspection on failure; do not DROP or restore over it.
ALTER TABLE appointment
    ADD COLUMN patient_complaint VARCHAR(500) NULL,
    ADD COLUMN complaint_updated_at DATETIME(6) NULL,
    ADD COLUMN complaint_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE clinical_draft (
    id VARCHAR(36) PRIMARY KEY,
    appointment_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(36) NOT NULL,
    complaint VARCHAR(500) NULL,
    diagnosis VARCHAR(500) NULL,
    advice VARCHAR(2000) NULL,
    notes VARCHAR(2000) NULL,
    saved_at DATETIME(6) NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT uk_clinical_draft_appointment UNIQUE (appointment_id),
    CONSTRAINT fk_clinical_draft_appointment FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    CONSTRAINT fk_clinical_draft_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_clinical_draft_doctor FOREIGN KEY (doctor_id) REFERENCES user_account(id),
    CONSTRAINT fk_clinical_draft_patient FOREIGN KEY (patient_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE record_correction (
    id VARCHAR(36) PRIMARY KEY,
    record_id VARCHAR(36) NOT NULL,
    appointment_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(36) NOT NULL,
    sequence_number BIGINT NOT NULL,
    before_complaint VARCHAR(500) NULL,
    before_diagnosis VARCHAR(500) NOT NULL,
    before_advice VARCHAR(2000) NOT NULL,
    before_notes VARCHAR(2000) NULL,
    after_complaint VARCHAR(500) NULL,
    after_diagnosis VARCHAR(500) NOT NULL,
    after_advice VARCHAR(2000) NOT NULL,
    after_notes VARCHAR(2000) NULL,
    reason VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    operation_key VARCHAR(36) NOT NULL,
    CONSTRAINT uk_record_correction_sequence UNIQUE (record_id, sequence_number),
    CONSTRAINT uk_record_correction_operation UNIQUE (doctor_id, operation_key),
    CONSTRAINT fk_record_correction_record FOREIGN KEY (record_id) REFERENCES clinical_record(id),
    CONSTRAINT fk_record_correction_appointment FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    CONSTRAINT fk_record_correction_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_record_correction_doctor FOREIGN KEY (doctor_id) REFERENCES user_account(id),
    CONSTRAINT fk_record_correction_patient FOREIGN KEY (patient_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
