CREATE TABLE department (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_department_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT fk_department_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

CREATE INDEX idx_department_tenant ON department(tenant_id);

CREATE TABLE doctor_profile (
    user_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    department_id VARCHAR(36) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    title VARCHAR(100) NOT NULL DEFAULT '',
    biography VARCHAR(1000) NOT NULL DEFAULT '',
    CONSTRAINT fk_doctor_profile_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT fk_doctor_profile_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT fk_doctor_profile_department FOREIGN KEY (department_id) REFERENCES department(id)
);

CREATE INDEX idx_doctor_profile_tenant ON doctor_profile(tenant_id);
CREATE INDEX idx_doctor_profile_department ON doctor_profile(department_id);
