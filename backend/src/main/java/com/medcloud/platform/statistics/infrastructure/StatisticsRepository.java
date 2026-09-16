package com.medcloud.platform.statistics.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class StatisticsRepository {
    private final JdbcTemplate jdbcTemplate;

    public StatisticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TenantAggregate hospital(UUID tenantId, LocalDate from, LocalDate to) {
        TimeWindow window = TimeWindow.of(from, to);
        long appointments = jdbcTemplate.queryForObject("""
                select count(*) from appointment
                where tenant_id = ? and appointment_date >= ? and appointment_date <= ?
                """, Long.class, tenantId.toString(), from, to);
        long completedConsultations = jdbcTemplate.queryForObject("""
                select count(*) from consultation
                where tenant_id = ? and status = 'COMPLETED'
                  and completed_at >= ? and completed_at < ?
                """, Long.class, tenantId.toString(), window.start(), window.endExclusive());
        PaymentAggregate payment = jdbcTemplate.queryForObject("""
                select count(*), coalesce(sum(amount), 0) from medical_order
                where tenant_id = ? and status = 'PAID'
                  and paid_at >= ? and paid_at < ?
                """, (resultSet, ignored) -> new PaymentAggregate(
                resultSet.getLong(1), resultSet.getBigDecimal(2)),
                tenantId.toString(), window.start(), window.endExclusive());
        return new TenantAggregate(tenantId, appointments, completedConsultations,
                payment.count(), payment.revenue());
    }

    /**
     * Every data-bearing query is grouped by tenant. The resulting aggregate carries no user,
     * consultation, prescription or order identifiers.
     */
    public List<TenantAggregate> platform(LocalDate from, LocalDate to) {
        TimeWindow window = TimeWindow.of(from, to);
        Map<UUID, MutableTenantAggregate> results = new HashMap<>();
        jdbcTemplate.query("""
                        select tenant_id, count(*) from appointment
                        where appointment_date >= ? and appointment_date <= ?
                        group by tenant_id
                        """,
                resultSet -> {
                    aggregate(results, resultSet.getString(1)).appointments = resultSet.getLong(2);
                },
                from, to);
        jdbcTemplate.query("""
                        select tenant_id, count(*) from consultation
                        where status = 'COMPLETED' and completed_at >= ? and completed_at < ?
                        group by tenant_id
                        """,
                resultSet -> {
                    aggregate(results, resultSet.getString(1)).completedConsultations = resultSet.getLong(2);
                },
                window.start(), window.endExclusive());
        jdbcTemplate.query("""
                        select tenant_id, count(*), coalesce(sum(amount), 0) from medical_order
                        where status = 'PAID' and paid_at >= ? and paid_at < ?
                        group by tenant_id
                        """,
                resultSet -> {
                    MutableTenantAggregate aggregate = aggregate(results, resultSet.getString(1));
                    aggregate.paidOrders = resultSet.getLong(2);
                    aggregate.paidRevenue = resultSet.getBigDecimal(3);
                },
                window.start(), window.endExclusive());
        return results.values().stream()
                .map(MutableTenantAggregate::immutable)
                .sorted((left, right) -> left.tenantId().compareTo(right.tenantId()))
                .toList();
    }

    private static MutableTenantAggregate aggregate(Map<UUID, MutableTenantAggregate> results, String tenantId) {
        UUID id = UUID.fromString(tenantId);
        return results.computeIfAbsent(id, MutableTenantAggregate::new);
    }

    private record PaymentAggregate(long count, BigDecimal revenue) {
    }

    public record TenantAggregate(
            UUID tenantId,
            long appointmentCount,
            long completedConsultationCount,
            long paidOrderCount,
            BigDecimal paidRevenue
    ) {
    }

    private static final class MutableTenantAggregate {
        private final UUID tenantId;
        private long appointments;
        private long completedConsultations;
        private long paidOrders;
        private BigDecimal paidRevenue = BigDecimal.ZERO;

        private MutableTenantAggregate(UUID tenantId) {
            this.tenantId = tenantId;
        }

        private TenantAggregate immutable() {
            return new TenantAggregate(tenantId, appointments, completedConsultations, paidOrders, paidRevenue);
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
