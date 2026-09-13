CREATE TABLE schedule_slot (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    department_id VARCHAR(36) NOT NULL,
    schedule_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    capacity INT NOT NULL,
    fee DECIMAL(10, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_schedule_slot_time_range CHECK (end_time > start_time),
    CONSTRAINT ck_schedule_slot_capacity CHECK (capacity > 0),
    CONSTRAINT ck_schedule_slot_fee CHECK (fee >= 0),
    CONSTRAINT fk_schedule_slot_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_schedule_slot_doctor FOREIGN KEY (doctor_id) REFERENCES user_account(id),
    CONSTRAINT fk_schedule_slot_department FOREIGN KEY (department_id) REFERENCES department(id)
);

CREATE INDEX idx_schedule_slot_catalog
    ON schedule_slot(status, schedule_date, tenant_id, department_id);
CREATE INDEX idx_schedule_slot_doctor_time
    ON schedule_slot(doctor_id, schedule_date, status, start_time, end_time);
