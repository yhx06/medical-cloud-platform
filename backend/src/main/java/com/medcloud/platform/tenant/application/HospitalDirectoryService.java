package com.medcloud.platform.tenant.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.identity.domain.UserStatus;
import com.medcloud.platform.identity.infrastructure.UserAccountRepository;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.tenant.domain.Department;
import com.medcloud.platform.tenant.domain.DoctorProfile;
import com.medcloud.platform.tenant.domain.TenantStatus;
import com.medcloud.platform.tenant.infrastructure.DepartmentRepository;
import com.medcloud.platform.tenant.infrastructure.DoctorProfileRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class HospitalDirectoryService {
    private final DepartmentRepository departments;
    private final DoctorProfileRepository doctorProfiles;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TenantAccessPolicy tenantAccessPolicy;

    public HospitalDirectoryService(
            DepartmentRepository departments,
            DoctorProfileRepository doctorProfiles,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            TenantAccessPolicy tenantAccessPolicy
    ) {
        this.departments = departments;
        this.doctorProfiles = doctorProfiles;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tenantAccessPolicy = tenantAccessPolicy;
    }

    @Transactional
    public DepartmentResponse createDepartment(CurrentUser actor, CreateDepartmentRequest request) {
        UUID tenantId = requireHospitalTenant(actor);
        String name = request.name().trim();
        requireUniqueDepartmentName(tenantId, name, null);
        Department department = Department.create(tenantId, name, normalize(request.description()));
        try {
            return DepartmentResponse.from(departments.saveAndFlush(department));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.DEPARTMENT_DUPLICATE);
        }
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> listDepartments(CurrentUser actor) {
        UUID tenantId = requireHospitalTenant(actor);
        return departments.findAllByTenantIdOrderByNameAsc(tenantId).stream()
                .map(DepartmentResponse::from)
                .toList();
    }

    @Transactional
    public DepartmentResponse updateDepartment(CurrentUser actor, UUID departmentId, UpdateDepartmentRequest request) {
        UUID tenantId = requireHospitalTenant(actor);
        Department department = departments.findById(departmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        tenantAccessPolicy.requireTenant(actor, department.getTenantId());
        String name = request.name().trim();
        requireUniqueDepartmentName(tenantId, name, departmentId);
        department.update(name, normalize(request.description()), tenantStatus(request.status()));
        try {
            return DepartmentResponse.from(departments.saveAndFlush(department));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.DEPARTMENT_DUPLICATE);
        }
    }

    @Transactional
    public DoctorResponse createDoctor(CurrentUser actor, CreateDoctorRequest request) {
        UUID tenantId = requireHospitalTenant(actor);
        Department department = departments.findById(request.departmentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        tenantAccessPolicy.requireTenant(actor, department.getTenantId());

        String username = request.username().trim();
        if (users.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        UserAccount account = users.save(UserAccount.createDoctor(
                tenantId, username, passwordEncoder.encode(request.password())));
        DoctorProfile profile = doctorProfiles.save(DoctorProfile.create(
                account.getId(), tenantId, department.getId(), request.displayName().trim(),
                normalize(request.title()), normalize(request.biography())));
        return DoctorResponse.from(account, profile);
    }

    @Transactional(readOnly = true)
    public List<DoctorResponse> listDoctors(CurrentUser actor) {
        UUID tenantId = requireHospitalTenant(actor);
        return doctorProfiles.findAllByTenantIdOrderByDisplayNameAsc(tenantId).stream()
                .map(profile -> DoctorResponse.from(account(profile.getUserId()), profile))
                .toList();
    }

    @Transactional
    public DoctorResponse changeDoctorStatus(CurrentUser actor, UUID doctorId, String status) {
        requireHospitalTenant(actor);
        DoctorProfile profile = doctorProfiles.findById(doctorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        tenantAccessPolicy.requireTenant(actor, profile.getTenantId());
        UserAccount account = account(doctorId);
        account.changeStatus(userStatus(status));
        return DoctorResponse.from(account, profile);
    }

    private void requireUniqueDepartmentName(UUID tenantId, String name, UUID currentId) {
        boolean duplicate = currentId == null
                ? departments.existsByTenantIdAndName(tenantId, name)
                : departments.existsByTenantIdAndNameAndIdNot(tenantId, name, currentId);
        if (duplicate) {
            throw new BusinessException(ErrorCode.DEPARTMENT_DUPLICATE);
        }
    }

    private UserAccount account(UUID id) {
        return users.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private UUID requireHospitalTenant(CurrentUser actor) {
        if (actor == null || actor.role() != UserRole.HOSPITAL_ADMIN || actor.tenantId() == null) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return actor.tenantId();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private TenantStatus tenantStatus(String value) {
        try {
            return TenantStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private UserStatus userStatus(String value) {
        try {
            return UserStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    public record CreateDepartmentRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description
    ) {
    }

    public record UpdateDepartmentRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank String status
    ) {
    }

    public record CreateDoctorRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull UUID departmentId,
            @NotBlank @Size(max = 100) String displayName,
            @Size(max = 100) String title,
            @Size(max = 1000) String biography
    ) {
    }

    public record DepartmentResponse(
            UUID id,
            UUID tenantId,
            String name,
            String description,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        static DepartmentResponse from(Department department) {
            return new DepartmentResponse(
                    department.getId(), department.getTenantId(), department.getName(), department.getDescription(),
                    department.getStatus().name(), department.getCreatedAt(), department.getUpdatedAt());
        }
    }

    public record DoctorResponse(
            UUID id,
            String username,
            UUID tenantId,
            UUID departmentId,
            String displayName,
            String title,
            String biography,
            String status
    ) {
        static DoctorResponse from(UserAccount account, DoctorProfile profile) {
            return new DoctorResponse(
                    account.getId(), account.getUsername(), profile.getTenantId(), profile.getDepartmentId(),
                    profile.getDisplayName(), profile.getTitle(), profile.getBiography(), account.getStatus().name());
        }
    }
}
