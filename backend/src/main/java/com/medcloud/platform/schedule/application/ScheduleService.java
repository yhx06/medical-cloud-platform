package com.medcloud.platform.schedule.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.identity.domain.UserStatus;
import com.medcloud.platform.identity.infrastructure.UserAccountRepository;
import com.medcloud.platform.schedule.domain.ScheduleSlot;
import com.medcloud.platform.schedule.domain.ScheduleSlotStatus;
import com.medcloud.platform.schedule.infrastructure.ScheduleSlotRepository;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.tenant.application.TenantAccessPolicy;
import com.medcloud.platform.tenant.domain.Department;
import com.medcloud.platform.tenant.domain.DoctorProfile;
import com.medcloud.platform.tenant.domain.Tenant;
import com.medcloud.platform.tenant.domain.TenantStatus;
import com.medcloud.platform.tenant.infrastructure.DepartmentRepository;
import com.medcloud.platform.tenant.infrastructure.DoctorProfileRepository;
import com.medcloud.platform.tenant.infrastructure.TenantRepository;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Service
public class ScheduleService {
    private final ScheduleSlotRepository slots;
    private final TenantRepository tenants;
    private final DepartmentRepository departments;
    private final DoctorProfileRepository doctorProfiles;
    private final UserAccountRepository users;
    private final TenantAccessPolicy tenantAccessPolicy;

    public ScheduleService(
            ScheduleSlotRepository slots,
            TenantRepository tenants,
            DepartmentRepository departments,
            DoctorProfileRepository doctorProfiles,
            UserAccountRepository users,
            TenantAccessPolicy tenantAccessPolicy
    ) {
        this.slots = slots;
        this.tenants = tenants;
        this.departments = departments;
        this.doctorProfiles = doctorProfiles;
        this.users = users;
        this.tenantAccessPolicy = tenantAccessPolicy;
    }

    @Transactional
    public ScheduleSlotResponse publish(CurrentUser actor, PublishSlotRequest request) {
        UUID tenantId = requireHospitalTenant(actor);
        DoctorProfile doctor = doctorProfiles.findById(request.doctorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        tenantAccessPolicy.requireTenant(actor, doctor.getTenantId());

        Department department = departments.findById(doctor.getDepartmentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_UNAVAILABLE));
        UserAccount account = users.findById(doctor.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_UNAVAILABLE));
        if (!tenantId.equals(department.getTenantId())
                || department.getStatus() != TenantStatus.ACTIVE
                || account.getStatus() != UserStatus.ACTIVE
                || account.getRole() != UserRole.DOCTOR
                || !tenantId.equals(account.getTenantId())) {
            throw new BusinessException(ErrorCode.SCHEDULE_UNAVAILABLE);
        }

        ScheduleSlot slot = ScheduleSlot.publish(
                tenantId,
                doctor.getUserId(),
                department.getId(),
                request.date(),
                request.startTime(),
                request.endTime(),
                request.capacity(),
                request.fee(),
                LocalDate.now());
        if (slots.existsByDoctorIdAndScheduleDateAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                slot.getDoctorId(), slot.getScheduleDate(), ScheduleSlotStatus.PUBLISHED,
                slot.getEndTime(), slot.getStartTime())) {
            throw new BusinessException(ErrorCode.SCHEDULE_OVERLAP);
        }
        slots.saveAndFlush(slot);
        return response(slot, tenantName(tenantId), department.getName(), doctor.getDisplayName());
    }

    @Transactional(readOnly = true)
    public Page<ScheduleSlotResponse> searchCatalog(
            UUID tenantId,
            UUID departmentId,
            LocalDate date,
            Pageable pageable
    ) {
        return slots.searchCatalog(
                        tenantId,
                        departmentId,
                        date,
                        LocalDate.now(),
                        ScheduleSlotStatus.PUBLISHED,
                        TenantStatus.ACTIVE,
                        UserStatus.ACTIVE,
                        pageable)
                .map(this::catalogResponse);
    }

    private ScheduleSlotResponse catalogResponse(ScheduleSlot slot) {
        Tenant tenant = tenants.findById(slot.getTenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Department department = departments.findById(slot.getDepartmentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        DoctorProfile doctor = doctorProfiles.findById(slot.getDoctorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return response(slot, tenant.getName(), department.getName(), doctor.getDisplayName());
    }

    private ScheduleSlotResponse response(
            ScheduleSlot slot,
            String tenantName,
            String departmentName,
            String doctorName
    ) {
        return new ScheduleSlotResponse(
                slot.getId(),
                slot.getTenantId(),
                tenantName,
                slot.getDepartmentId(),
                departmentName,
                slot.getDoctorId(),
                doctorName,
                slot.getScheduleDate(),
                slot.getStartTime(),
                slot.getEndTime(),
                slot.getCapacity(),
                slot.getCapacity(),
                slot.getFee(),
                slot.getStatus().name(),
                slot.getCreatedAt());
    }

    private String tenantName(UUID tenantId) {
        return tenants.findByIdAndStatus(tenantId, TenantStatus.ACTIVE)
                .map(Tenant::getName)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_UNAVAILABLE));
    }

    private UUID requireHospitalTenant(CurrentUser actor) {
        if (actor == null || actor.role() != UserRole.HOSPITAL_ADMIN || actor.tenantId() == null) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return actor.tenantId();
    }

    public record PublishSlotRequest(
            @NotNull UUID doctorId,
            @NotNull LocalDate date,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @Positive int capacity,
            @NotNull @DecimalMin("0.00") BigDecimal fee
    ) {
    }

    public record ScheduleSlotResponse(
            UUID id,
            UUID tenantId,
            String tenantName,
            UUID departmentId,
            String departmentName,
            UUID doctorId,
            String doctorName,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            int capacity,
            int remainingCapacity,
            BigDecimal fee,
            String status,
            Instant createdAt
    ) {
    }
}
