package com.medcloud.platform.statistics.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class StatisticsRepository {
    private final JdbcTemplate jdbcTemplate;

    public StatisticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public HospitalAggregate hospital(UUID tenantId, LocalDate from, LocalDate to) {
        TimeWindow window = TimeWindow.of(from, to);
        long totalAppointments = appointmentCount(tenantId, from, to, null);
        long cancelledAppointments = appointmentCount(tenantId, from, to, "CANCELLED");
        long completedAppointments = appointmentCount(tenantId, from, to, "COMPLETED");
        long completedConsultations = jdbcTemplate.queryForObject("""
                select count(*) from consultation
                where tenant_id = ? and status = 'COMPLETED'
                  and completed_at >= ? and completed_at < ?
                """, Long.class, tenantId.toString(), window.start(), window.endExclusive());
        BigDecimal paidRevenue = jdbcTemplate.queryForObject("""
                select coalesce(sum(amount), 0) from medical_order
                where tenant_id = ? and status = 'PAID'
                  and paid_at >= ? and paid_at < ?
                """, BigDecimal.class, tenantId.toString(), window.start(), window.endExclusive());
        return new HospitalAggregate(
                tenantId,
                totalAppointments,
                cancelledAppointments,
                completedAppointments,
                completedConsultations,
                paidRevenue);
    }

    /**
     * Account totals use user-account creation time in the same inclusive period as every other
     * aggregate. Tenant rows are initialized first so inactive and zero-activity tenants remain
     * visible without exposing tenant names or personal details.
     */
    public List<PlatformAggregate> platform(LocalDate from, LocalDate to) {
        TimeWindow window = TimeWindow.of(from, to);
        Map<UUID, MutablePlatformAggregate> results = new LinkedHashMap<>();
        jdbcTemplate.query("select id, status from tenant", resultSet -> {
            UUID tenantId = UUID.fromString(resultSet.getString(1));
            results.put(tenantId, new MutablePlatformAggregate(tenantId, resultSet.getString(2)));
        });
        jdbcTemplate.query("""
                        select tenant_id, count(*) from user_account
                        where tenant_id is not null and created_at >= ? and created_at < ?
                        group by tenant_id
                        """, resultSet -> {
                    aggregate(results, resultSet.getString(1)).accountCount = resultSet.getLong(2);
                },
                window.start(), window.endExclusive());
        jdbcTemplate.query("""
                        select tenant_id, count(*) from appointment
                        where appointment_date >= ? and appointment_date <= ?
                        group by tenant_id
                        """, resultSet -> {
                    aggregate(results, resultSet.getString(1)).appointmentCount = resultSet.getLong(2);
                },
                from, to);
        jdbcTemplate.query("""
                        select tenant_id, count(*) from appointment
                        where status = 'COMPLETED' and appointment_date >= ? and appointment_date <= ?
                        group by tenant_id
                        """, resultSet -> {
                    aggregate(results, resultSet.getString(1)).completedAppointmentCount = resultSet.getLong(2);
                },
                from, to);
        jdbcTemplate.query("""
                        select tenant_id, coalesce(sum(amount), 0) from medical_order
                        where status = 'PAID' and paid_at >= ? and paid_at < ?
                        group by tenant_id
                        """, resultSet -> {
                    aggregate(results, resultSet.getString(1)).paidRevenue = resultSet.getBigDecimal(2);
                },
                window.start(), window.endExclusive());
        return results.values().stream()
                .map(MutablePlatformAggregate::immutable)
                .sorted((left, right) -> left.tenantId().compareTo(right.tenantId()))
                .toList();
    }

    private long appointmentCount(UUID tenantId, LocalDate from, LocalDate to, String status) {
        if (status == null) {
            return jdbcTemplate.queryForObject("""
                    select count(*) from appointment
                    where tenant_id = ? and appointment_date >= ? and appointment_date <= ?
                    """, Long.class, tenantId.toString(), from, to);
        }
        return jdbcTemplate.queryForObject("""
                select count(*) from appointment
                where tenant_id = ? and status = ? and appointment_date >= ? and appointment_date <= ?
                """, Long.class, tenantId.toString(), status, from, to);
    }

    private static MutablePlatformAggregate aggregate(Map<UUID, MutablePlatformAggregate> results, String tenantId) {
        UUID id = UUID.fromString(tenantId);
        return results.computeIfAbsent(id, ignored -> new MutablePlatformAggregate(id, null));
    }

    public record HospitalAggregate(
            UUID tenantId,
            long totalAppointmentCount,
            long cancelledAppointmentCount,
            long completedAppointmentCount,
            long doctorCompletedConsultationCount,
            BigDecimal paidRevenue
    ) {
    }

    public record PlatformAggregate(
            UUID tenantId,
            String tenantStatus,
            long accountCount,
            long appointmentCount,
            long completedAppointmentCount,
            BigDecimal paidRevenue
    ) {
    }

    private static final class MutablePlatformAggregate {
        private final UUID tenantId;
        private final String tenantStatus;
        private long accountCount;
        private long appointmentCount;
        private long completedAppointmentCount;
        private BigDecimal paidRevenue = BigDecimal.ZERO;

        private MutablePlatformAggregate(UUID tenantId, String tenantStatus) {
            this.tenantId = tenantId;
            this.tenantStatus = tenantStatus;
        }

        private PlatformAggregate immutable() {
            return new PlatformAggregate(
                    tenantId,
                    tenantStatus,
                    accountCount,
                    appointmentCount,
                    completedAppointmentCount,
                    paidRevenue);
        }
    }

    private record TimeWindow(Timestamp start, Timestamp endExclusive) {
        private static TimeWindow of(LocalDate from, LocalDate to) {
            return new TimeWindow(
                    Timestamp.valueOf(from.atStartOfDay()),
                    Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
    }
}
