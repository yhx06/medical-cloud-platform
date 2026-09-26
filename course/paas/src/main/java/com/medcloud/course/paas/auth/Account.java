package com.medcloud.course.paas.auth;

import com.medcloud.course.contract.Role;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(name = "user_account", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class Account {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    protected Account() { }

    public Account(String id, String username, String passwordHash, Role role, String tenantId) {
        if (id == null || username == null || passwordHash == null || role == null) {
            throw new IllegalArgumentException("account fields required");
        }
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.tenantId = tenantId;
        this.status = "ACTIVE";
    }

    public void disable() { status = "DISABLED"; }
    public void setStatus(String status) { this.status = status; }
    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public String getStatus() { return status; }
    public String getTenantId() { return tenantId; }
}
