package com.medcloud.course.paas.tenant;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "doctor_profile")
public class DoctorProfile {
    @Id
    @Column(name = "user_id", length = 36)
    private String userId;
    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;
    @Column(name = "department_id", nullable = false, length = 36)
    private String departmentId;
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;
    protected DoctorProfile() { }
    public DoctorProfile(String userId, String tenantId, String departmentId, String displayName) {
        this.userId = userId; this.tenantId = tenantId; this.departmentId = departmentId; this.displayName = displayName;
    }
    public String getUserId() { return userId; }
    public String getTenantId() { return tenantId; }
    public String getDepartmentId() { return departmentId; }
    public String getDisplayName() { return displayName; }
    public void update(String displayName, String departmentId) { this.displayName = displayName; this.departmentId = departmentId; }
}
