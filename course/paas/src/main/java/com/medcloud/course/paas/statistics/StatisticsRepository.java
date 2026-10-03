package com.medcloud.course.paas.statistics;

import com.medcloud.course.contract.*;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.math.BigDecimal;
import java.util.*;

/** Scalar-only SQL adapter. Query count is independent of hospitals and calendar days. */
public final class StatisticsRepository {
    private final EntityManager manager;

    public StatisticsRepository(EntityManager manager) { this.manager = manager; }

    public List<TenantView> tenants(String tenantId) {
        Query query = manager.createNativeQuery("select t.id, t.name, t.status from tenant t"
                + (tenantId == null ? "" : " where t.id = :tenantId") + " order by t.name, t.id");
        if (tenantId != null) query.setParameter("tenantId", tenantId);
        List<TenantView> result = new ArrayList<TenantView>();
        for (Object value : query.getResultList()) {
            Object[] row = (Object[]) value;
            result.add(new TenantView((String) row[0], (String) row[1], (String) row[2]));
        }
        return result;
    }

    public Map<String, List<DailyStatistics>> daily(StatisticsRange range, String tenantId) {
        Map<String, SortedMap<String, StatisticsMetrics>> values = new LinkedHashMap<String, SortedMap<String, StatisticsMetrics>>();
        String cohort = "select a.tenant_id, cast(a.booked_on as char(10)), count(*), "
                + "sum(case when a.status = 'BOOKED' then 1 else 0 end), "
                + "sum(case when a.status = 'IN_PROGRESS' then 1 else 0 end), "
                + "sum(case when a.status = 'CANCELED' then 1 else 0 end), "
                + "sum(case when a.status = 'COMPLETED' then 1 else 0 end) "
                + "from appointment a where a.booked_on >= :fromDate and a.booked_on <= :toDate"
                + scope("a", tenantId) + " group by a.tenant_id, cast(a.booked_on as char(10))";
        addDaily(values, dated(cohort, range, tenantId, false).getResultList(), 0);
        String started = "select a.tenant_id, cast(date(a.started_at) as char(10)), count(*) from appointment a "
                + "where a.started_at >= :startTime and a.started_at < :endTime"
                + scope("a", tenantId) + " group by a.tenant_id, cast(date(a.started_at) as char(10))";
        addDaily(values, dated(started, range, tenantId, true).getResultList(), 1);
        String completed = "select r.tenant_id, cast(date(r.completed_at) as char(10)), count(*) from clinical_record r "
                + "where r.completed_at >= :startTime and r.completed_at < :endTime"
                + scope("r", tenantId) + " group by r.tenant_id, cast(date(r.completed_at) as char(10))";
        addDaily(values, dated(completed, range, tenantId, true).getResultList(), 2);
        String generated = "select o.tenant_id, cast(date(r.completed_at) as char(10)), count(*), sum(o.amount) "
                + "from payment_order o join clinical_record r "
                + "on r.appointment_id = o.appointment_id and r.tenant_id = o.tenant_id "
                + "where r.completed_at >= :startTime and r.completed_at < :endTime"
                + scope("o", tenantId) + " group by o.tenant_id, cast(date(r.completed_at) as char(10))";
        addDaily(values, dated(generated, range, tenantId, true).getResultList(), 3);
        String paid = "select o.tenant_id, cast(date(o.paid_at) as char(10)), count(*), sum(o.amount) from payment_order o "
                + "where o.status = 'PAID' and o.paid_at >= :startTime and o.paid_at < :endTime"
                + scope("o", tenantId) + " group by o.tenant_id, cast(date(o.paid_at) as char(10))";
        addDaily(values, dated(paid, range, tenantId, true).getResultList(), 4);
        Map<String, List<DailyStatistics>> result = new LinkedHashMap<String, List<DailyStatistics>>();
        for (Map.Entry<String, SortedMap<String, StatisticsMetrics>> tenant : values.entrySet()) {
            List<DailyStatistics> days = new ArrayList<DailyStatistics>();
            for (Map.Entry<String, StatisticsMetrics> day : tenant.getValue().entrySet())
                days.add(new DailyStatistics(day.getKey(), day.getValue()));
            result.put(tenant.getKey(), days);
        }
        return result;
    }

    public Map<String, LegacyUnknownTime> legacy(String tenantId) {
        Map<String, LegacyUnknownTime> result = new LinkedHashMap<String, LegacyUnknownTime>();
        String started = "select a.tenant_id, count(*) from appointment a "
                + "where a.status in ('IN_PROGRESS', 'COMPLETED') and a.started_at is null"
                + scope("a", tenantId) + " group by a.tenant_id";
        addLegacy(result, scoped(started, tenantId).getResultList(), 0);
        String completed = "select r.tenant_id, count(*) from clinical_record r where r.completed_at is null"
                + scope("r", tenantId) + " group by r.tenant_id";
        addLegacy(result, scoped(completed, tenantId).getResultList(), 1);
        String generated = "select o.tenant_id, count(*), sum(o.amount) from payment_order o "
                + "left join clinical_record r on r.appointment_id = o.appointment_id and r.tenant_id = o.tenant_id "
                + "where r.completed_at is null"
                + scope("o", tenantId) + " group by o.tenant_id";
        addLegacy(result, scoped(generated, tenantId).getResultList(), 2);
        String paid = "select o.tenant_id, count(*), sum(o.amount) from payment_order o "
                + "where o.status = 'PAID' and o.paid_at is null"
                + scope("o", tenantId) + " group by o.tenant_id";
        addLegacy(result, scoped(paid, tenantId).getResultList(), 3);
        return result;
    }

    private String scope(String alias, String tenantId) {
        return tenantId == null ? "" : " and " + alias + ".tenant_id = :tenantId";
    }

    private Query scoped(String sql, String tenantId) {
        Query query = manager.createNativeQuery(sql);
        if (tenantId != null) query.setParameter("tenantId", tenantId);
        return query;
    }

    private Query dated(String sql, StatisticsRange range, String tenantId, boolean event) {
        Query query = scoped(sql, tenantId);
        if (event) {
            // MySQL DATETIME stores Shanghai wall time, not UTC. String parameters avoid
            // introducing a JDBC/JVM timezone conversion while remaining fully bound.
            query.setParameter("startTime", range.getFrom().toString() + " 00:00:00");
            query.setParameter("endTime", range.getTo().plusDays(1).toString() + " 00:00:00");
        } else {
            query.setParameter("fromDate", range.getFrom().toString());
            query.setParameter("toDate", range.getTo().toString());
        }
        return query;
    }

    private void addDaily(Map<String, SortedMap<String, StatisticsMetrics>> result, List<?> rows, int kind) {
        for (Object value : rows) {
            Object[] row = (Object[]) value;
            String tenant = (String) row[0];
            // SQL returns the local calendar date as text; JDBC Date conversion
            // must not shift a grouped date when the JVM default timezone changes.
            String day = (String) row[1];
            long[] counts = new long[9];
            BigDecimal order = BigDecimal.ZERO;
            BigDecimal revenue = BigDecimal.ZERO;
            if (kind == 0) {
                for (int i = 0; i < 5; i++) counts[i] = count(row[i + 2]);
            } else {
                counts[kind + 4] = count(row[2]);
                if (kind == 3) order = money(row[3]);
                if (kind == 4) revenue = money(row[3]);
            }
            StatisticsMetrics contribution = new StatisticsMetrics(counts[0], counts[1], counts[2], counts[3],
                    counts[4], counts[5], counts[6], counts[7], counts[8], order, revenue);
            SortedMap<String, StatisticsMetrics> days = result.get(tenant);
            if (days == null) {
                days = new TreeMap<String, StatisticsMetrics>();
                result.put(tenant, days);
            }
            StatisticsMetrics previous = days.get(day);
            days.put(day, previous == null ? contribution : previous.plus(contribution));
        }
    }

    private void addLegacy(Map<String, LegacyUnknownTime> result, List<?> rows, int kind) {
        for (Object value : rows) {
            Object[] row = (Object[]) value;
            long[] counts = new long[4];
            counts[kind] = count(row[1]);
            LegacyUnknownTime contribution = new LegacyUnknownTime(counts[0], counts[1], counts[2], counts[3],
                    kind == 2 ? money(row[2]) : BigDecimal.ZERO, kind == 3 ? money(row[2]) : BigDecimal.ZERO);
            String tenant = (String) row[0];
            LegacyUnknownTime previous = result.get(tenant);
            result.put(tenant, previous == null ? contribution : previous.plus(contribution));
        }
    }

    private long count(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private BigDecimal money(Object value) { return value == null ? BigDecimal.ZERO : (BigDecimal) value; }
}
