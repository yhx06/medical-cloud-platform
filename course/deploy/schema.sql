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
