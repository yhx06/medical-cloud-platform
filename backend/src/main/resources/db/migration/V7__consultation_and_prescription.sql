CREATE TABLE consultation (
    id VARCHAR(36) PRIMARY KEY,
    appointment_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    complaint TEXT NOT NULL,
    diagnosis TEXT NOT NULL,
    advice TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    draft_saved_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_consultation_appointment UNIQUE (appointment_id),
    CONSTRAINT fk_consultation_appointment FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    CONSTRAINT fk_consultation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_consultation_patient FOREIGN KEY (patient_id) REFERENCES user_account(id),
    CONSTRAINT fk_consultation_doctor FOREIGN KEY (doctor_id) REFERENCES user_account(id)
);

CREATE INDEX idx_consultation_patient_records
    ON consultation(patient_id, status, completed_at);
CREATE INDEX idx_consultation_doctor_records
    ON consultation(tenant_id, doctor_id, status, completed_at);

CREATE TABLE prescription_item (
    id VARCHAR(36) PRIMARY KEY,
    consultation_id VARCHAR(36) NOT NULL,
    sequence_number INT NOT NULL,
    medication_name VARCHAR(200) NOT NULL,
    dosage VARCHAR(100) NOT NULL,
    frequency VARCHAR(100) NOT NULL,
    duration_days INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_prescription_item_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_prescription_item_duration CHECK (duration_days > 0),
    CONSTRAINT uk_prescription_item_sequence UNIQUE (consultation_id, sequence_number),
    CONSTRAINT fk_prescription_item_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultation(id)
);

CREATE INDEX idx_prescription_item_consultation
    ON prescription_item(consultation_id, sequence_number);

CREATE TABLE record_correction (
    id VARCHAR(36) PRIMARY KEY,
    consultation_id VARCHAR(36) NOT NULL,
    sequence_number INT NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_record_correction_sequence CHECK (sequence_number > 0),
    CONSTRAINT uk_record_correction_sequence UNIQUE (consultation_id, sequence_number),
    CONSTRAINT fk_record_correction_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultation(id),
    CONSTRAINT fk_record_correction_doctor FOREIGN KEY (doctor_id) REFERENCES user_account(id)
);

CREATE INDEX idx_record_correction_consultation
    ON record_correction(consultation_id, sequence_number);
