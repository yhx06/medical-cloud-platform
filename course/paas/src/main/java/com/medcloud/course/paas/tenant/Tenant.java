package com.medcloud.course.paas.tenant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "tenant")
public class Tenant {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 16)
    private String status;

    protected Tenant() {
    }

    public Tenant(String id, String name) {
        this.id = id;
        this.name = name;
        this.status = "ACTIVE";
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }
    public void setStatus(String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status))
            throw new IllegalArgumentException("invalid hospital status");
        this.status = status;
    }
    public void rename(String name) {
        if (name == null || name.isEmpty() || name.length() > 100)
            throw new IllegalArgumentException("invalid hospital name");
        this.name = name;
    }
}
