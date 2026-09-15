CREATE TABLE medical_order (
    id VARCHAR(36) PRIMARY KEY,
    consultation_id VARCHAR(36) NOT NULL,
    appointment_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(36) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    transaction_no VARCHAR(64) NULL,
    paid_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_medical_order_amount CHECK (amount >= 0),
    CONSTRAINT uk_medical_order_consultation UNIQUE (consultation_id),
    CONSTRAINT uk_medical_order_transaction UNIQUE (transaction_no),
    CONSTRAINT fk_medical_order_consultation
        FOREIGN KEY (consultation_id) REFERENCES consultation(id),
    CONSTRAINT fk_medical_order_appointment
        FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    CONSTRAINT fk_medical_order_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_medical_order_patient FOREIGN KEY (patient_id) REFERENCES user_account(id)
);

CREATE INDEX idx_medical_order_patient_list
    ON medical_order(patient_id, created_at);
CREATE INDEX idx_medical_order_tenant_status
    ON medical_order(tenant_id, status, created_at);
