package com.medcloud.platform.statistics.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.statistics.infrastructure.StatisticsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class StatisticsService {
    private static final long MAX_INCLUSIVE_DAYS = 366;

    private final StatisticsRepository statistics;

    public StatisticsService(StatisticsRepository statistics) {
        this.statistics = statistics;
    }

    @Transactional(readOnly = true)
    public HospitalStatisticsResponse hospital(CurrentUser admin, LocalDate from, LocalDate to) {
        UUID tenantId = requireHospitalAdmin(admin);
        validateRange(from, to);
        return hospitalResponse(statistics.hospital(tenantId, from, to), from, to);
    }

    @Transactional(readOnly = true)
    public PlatformStatisticsResponse platform(CurrentUser admin, LocalDate from, LocalDate to) {
        requirePlatformAdmin(admin);
        validateRange(from, to);
        return new PlatformStatisticsResponse(from, to, statistics.platform(from, to).stream()
                .map(StatisticsService::tenantResponse)
                .toList());
    }

    private static HospitalStatisticsResponse hospitalResponse(
            StatisticsRepository.TenantAggregate aggregate,
            LocalDate from,
            LocalDate to
    ) {
        return new HospitalStatisticsResponse(
                aggregate.tenantId(), from, to, aggregate.appointmentCount(),
                aggregate.completedConsultationCount(), aggregate.paidOrderCount(), aggregate.paidRevenue());
    }

    private static TenantStatisticsResponse tenantResponse(StatisticsRepository.TenantAggregate aggregate) {
        return new TenantStatisticsResponse(
                aggregate.tenantId(), aggregate.appointmentCount(), aggregate.completedConsultationCount(),
                aggregate.paidOrderCount(), aggregate.paidRevenue());
    }

    private static UUID requireHospitalAdmin(CurrentUser actor) {
        if (actor == null || actor.role() != UserRole.HOSPITAL_ADMIN || actor.tenantId() == null) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return actor.tenantId();
    }

    private static void requirePlatformAdmin(CurrentUser actor) {
        if (actor == null || actor.role() != UserRole.PLATFORM_ADMIN) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) + 1 > MAX_INCLUSIVE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    public record HospitalStatisticsResponse(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            long appointmentCount,
            long completedConsultationCount,
            long paidOrderCount,
            BigDecimal paidRevenue
    ) {
    }

    public record PlatformStatisticsResponse(
            LocalDate from,
            LocalDate to,
            List<TenantStatisticsResponse> tenants
    ) {
    }

    public record TenantStatisticsResponse(
            UUID tenantId,
            long appointmentCount,
            long completedConsultationCount,
            long paidOrderCount,
            BigDecimal paidRevenue
    ) {
    }
}
