package com.medcloud.course.paas.tenant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "department")
public class Department {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 16)
    private String status;

    protected Department() {
    }

    public Department(String id, String tenantId, String name) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.status = "ACTIVE";
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }
}
