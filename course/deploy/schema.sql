CREATE DATABASE IF NOT EXISTS medical_cloud_course
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE medical_cloud_course;

CREATE TABLE IF NOT EXISTS tenant (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS department (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT fk_department_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT uk_department_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS user_account (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    tenant_id VARCHAR(36),
    CONSTRAINT uk_course_username UNIQUE (username),
    CONSTRAINT fk_course_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

CREATE TABLE IF NOT EXISTS doctor_profile (
    user_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    department_id VARCHAR(36) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    CONSTRAINT fk_course_doctor_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_course_doctor_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_course_doctor_department FOREIGN KEY (department_id) REFERENCES department(id)
);

CREATE TABLE IF NOT EXISTS schedule_slot (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    department_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    schedule_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    capacity INT NOT NULL,
    fee DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT fk_course_slot_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_course_slot_department FOREIGN KEY (department_id) REFERENCES department(id),
    CONSTRAINT fk_course_slot_doctor FOREIGN KEY (doctor_id) REFERENCES user_account(id),
    CONSTRAINT uk_course_doctor_slot UNIQUE (doctor_id, schedule_date, start_time),
    INDEX idx_course_slot_catalog (tenant_id, status, schedule_date)
);

CREATE TABLE IF NOT EXISTS appointment (
    id VARCHAR(36) PRIMARY KEY,
    patient_id VARCHAR(36) NOT NULL,
    slot_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    fee DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    booked_on DATE NOT NULL,
    booked_at DATETIME(6) NULL,
    started_at DATETIME NULL,
    patient_complaint VARCHAR(500) NULL,
    complaint_updated_at DATETIME(6) NULL,
    complaint_revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_course_appointment_patient FOREIGN KEY (patient_id) REFERENCES user_account(id),
    CONSTRAINT fk_course_appointment_slot FOREIGN KEY (slot_id) REFERENCES schedule_slot(id),
    CONSTRAINT fk_course_appointment_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_course_appointment_doctor FOREIGN KEY (doctor_id) REFERENCES user_account(id),
    INDEX idx_course_patient_slot (patient_id, slot_id),
    INDEX idx_course_doctor_appointment (doctor_id, tenant_id, booked_on)
);

CREATE TABLE IF NOT EXISTS clinical_record (
    id VARCHAR(36) PRIMARY KEY,
    appointment_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(36) NOT NULL,
    diagnosis VARCHAR(500) NOT NULL,
    advice VARCHAR(2000) NOT NULL,
    complaint VARCHAR(500) NULL,
    notes VARCHAR(2000) NULL,
    completed_at DATETIME NULL,
    CONSTRAINT uk_course_record_appointment UNIQUE (appointment_id),
    CONSTRAINT fk_course_record_appointment FOREIGN KEY (appointment_id) REFERENCES appointment(id)
);

CREATE TABLE IF NOT EXISTS payment_order (
    id VARCHAR(36) PRIMARY KEY,
    appointment_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    paid_at DATETIME NULL,
    CONSTRAINT uk_course_order_appointment UNIQUE (appointment_id),
    CONSTRAINT fk_course_order_appointment FOREIGN KEY (appointment_id) REFERENCES appointment(id)
);

CREATE TABLE IF NOT EXISTS audit_event (
    id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NULL,
    actor_role VARCHAR(32) NULL,
    tenant_id VARCHAR(36) NULL,
    event_type VARCHAR(64) NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id VARCHAR(36) NULL,
    result VARCHAR(16) NOT NULL,
    reason_code VARCHAR(32) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_audit_request_event UNIQUE (request_id, event_type),
    INDEX idx_audit_recorded (recorded_at, id),
    INDEX idx_audit_tenant (tenant_id, recorded_at, id),
    INDEX idx_audit_event_result (event_type, result, recorded_at, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS file_metadata (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    appointment_id VARCHAR(36) NOT NULL,
    patient_id VARCHAR(36) NOT NULL,
    doctor_id VARCHAR(36) NOT NULL,
    uploader_id VARCHAR(36) NOT NULL,
    record_id VARCHAR(36) NULL,
    source_type VARCHAR(32) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    upload_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    attempt_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    retry_count INT NOT NULL,
    next_retry_at DATETIME(6) NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_attachment_upload UNIQUE (uploader_id, upload_key),
    CONSTRAINT uk_attachment_object UNIQUE (object_key),
    INDEX idx_attachment_listing (appointment_id, source_type, state, created_at, id),
    INDEX idx_attachment_tenant (tenant_id, state),
    INDEX idx_attachment_cleanup (state, next_retry_at, id),
    INDEX idx_attachment_lease (state, lease_until, id),
    CONSTRAINT fk_attachment_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_attachment_appointment FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    CONSTRAINT fk_attachment_patient FOREIGN KEY (patient_id) REFERENCES user_account(id),
    CONSTRAINT fk_attachment_doctor FOREIGN KEY (doctor_id) REFERENCES user_account(id),
    CONSTRAINT fk_attachment_uploader FOREIGN KEY (uploader_id) REFERENCES user_account(id),
    CONSTRAINT fk_attachment_record FOREIGN KEY (record_id) REFERENCES clinical_record(id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinical_draft (
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

CREATE TABLE IF NOT EXISTS record_correction (
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

CREATE TABLE IF NOT EXISTS hospital_admin_operation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    actor_id VARCHAR(36) NOT NULL,
    operation_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    admin_id VARCHAR(36) NOT NULL,
    hospital_name VARCHAR(100) NOT NULL,
    admin_username VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_hospital_admin_operation_actor_key UNIQUE (actor_id, operation_key),
    CONSTRAINT uk_hospital_admin_operation_admin UNIQUE (admin_id),
    INDEX idx_hospital_admin_operation_tenant (tenant_id),
    CONSTRAINT fk_hospital_admin_operation_actor FOREIGN KEY (actor_id) REFERENCES user_account(id),
    CONSTRAINT fk_hospital_admin_operation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_hospital_admin_operation_admin FOREIGN KEY (admin_id) REFERENCES user_account(id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
