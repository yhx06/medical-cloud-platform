CREATE TABLE appointment (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    department_id VARCHAR(36) NOT NULL,
    slot_id VARCHAR(36) NOT NULL,
    appointment_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    fee DECIMAL(10, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_patient_id VARCHAR(36) GENERATED ALWAYS AS (
        CASE WHEN status = 'CANCELLED' THEN NULL ELSE patient_id END
    ),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_appointment_time_range CHECK (end_time > start_time),
    CONSTRAINT ck_appointment_fee CHECK (fee >= 0),
    CONSTRAINT uk_appointment_active_booking UNIQUE (slot_id, active_patient_id),
    CONSTRAINT fk_appointment_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_appointment_patient FOREIGN KEY (patient_id) REFERENCES user_account(id),
    CONSTRAINT fk_appointment_doctor FOREIGN KEY (doctor_id) REFERENCES user_account(id),
    CONSTRAINT fk_appointment_department FOREIGN KEY (department_id) REFERENCES department(id),
    CONSTRAINT fk_appointment_slot FOREIGN KEY (slot_id) REFERENCES schedule_slot(id)
);

CREATE INDEX idx_appointment_patient_list
    ON appointment(patient_id, appointment_date, start_time);
CREATE INDEX idx_appointment_doctor_list
    ON appointment(tenant_id, doctor_id, appointment_date, start_time);
CREATE INDEX idx_appointment_slot_capacity
    ON appointment(slot_id, status);
