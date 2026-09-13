package com.medcloud.platform.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "doctor_profile")
public class DoctorProfile {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id")
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "department_id", nullable = false)
    private UUID departmentId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 1000)
    private String biography;

    protected DoctorProfile() {
    }

    private DoctorProfile(
            UUID userId,
            UUID tenantId,
            UUID departmentId,
            String displayName,
            String title,
            String biography
    ) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.departmentId = departmentId;
        this.displayName = displayName;
        this.title = title;
        this.biography = biography;
    }

    public static DoctorProfile create(
            UUID userId,
            UUID tenantId,
            UUID departmentId,
            String displayName,
            String title,
            String biography
    ) {
        return new DoctorProfile(userId, tenantId, departmentId, displayName, title, biography);
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getTitle() {
        return title;
    }

    public String getBiography() {
        return biography;
    }
}
