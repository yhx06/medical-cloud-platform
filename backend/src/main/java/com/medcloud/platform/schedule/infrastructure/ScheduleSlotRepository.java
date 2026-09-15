package com.medcloud.platform.schedule.infrastructure;

import com.medcloud.platform.identity.domain.UserStatus;
import com.medcloud.platform.schedule.domain.ScheduleSlot;
import com.medcloud.platform.schedule.domain.ScheduleSlotStatus;
import com.medcloud.platform.tenant.domain.TenantStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleSlotRepository extends JpaRepository<ScheduleSlot, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select slot from ScheduleSlot slot where slot.id = :id")
    Optional<ScheduleSlot> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByDoctorIdAndScheduleDateAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            UUID doctorId,
            LocalDate scheduleDate,
            ScheduleSlotStatus status,
            LocalTime requestedEnd,
            LocalTime requestedStart
    );

    @Query("""
            select slot
            from ScheduleSlot slot
            where slot.status = :slotStatus
              and slot.scheduleDate >= :minimumDate
              and (:tenantId is null or slot.tenantId = :tenantId)
              and (:departmentId is null or slot.departmentId = :departmentId)
              and (:date is null or slot.scheduleDate = :date)
              and exists (
                  select tenant.id from Tenant tenant
                  where tenant.id = slot.tenantId and tenant.status = :activeTenantStatus
              )
              and exists (
                  select department.id from Department department
                  where department.id = slot.departmentId
                    and department.tenantId = slot.tenantId
                    and department.status = :activeTenantStatus
              )
              and exists (
                  select profile.userId from DoctorProfile profile
                  where profile.userId = slot.doctorId
                    and profile.tenantId = slot.tenantId
                    and profile.departmentId = slot.departmentId
              )
              and exists (
                  select account.id from UserAccount account
                  where account.id = slot.doctorId
                    and account.tenantId = slot.tenantId
                    and account.status = :activeUserStatus
              )
            order by slot.scheduleDate asc, slot.startTime asc, slot.id asc
            """)
    Page<ScheduleSlot> searchCatalog(
            @Param("tenantId") UUID tenantId,
            @Param("departmentId") UUID departmentId,
            @Param("date") LocalDate date,
            @Param("minimumDate") LocalDate minimumDate,
            @Param("slotStatus") ScheduleSlotStatus slotStatus,
            @Param("activeTenantStatus") TenantStatus activeTenantStatus,
            @Param("activeUserStatus") UserStatus activeUserStatus,
            Pageable pageable
    );
}
