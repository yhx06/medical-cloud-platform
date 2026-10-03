package com.medcloud.course.paas.statistics;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.TenantAccessPolicy;
import javax.ejb.*;
import javax.persistence.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Stateless
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class StatisticsService {
    @PersistenceContext(unitName = "medicalCloudCourse") private EntityManager manager;
    private Clock clock = Clock.system(StatisticsRange.ZONE);

    public StatisticsService() { }
    public StatisticsService(EntityManager manager, Clock clock) { this.manager = manager; this.clock = clock; }

    public StatisticsReport hospital(CurrentActor actor, String from, String to) {
        String tenantId = new TenantAccessPolicy(manager).readableTenant(actor, Role.HOSPITAL_ADMIN).getId();
        return report("HOSPITAL", StatisticsRange.parse(from, to, clock), tenantId);
    }

    public StatisticsReport platform(CurrentActor actor, String from, String to) {
        new TenantAccessPolicy(manager).requireAccount(actor, Role.PLATFORM_ADMIN);
        return report("PLATFORM", StatisticsRange.parse(from, to, clock), null);
    }

    private StatisticsReport report(String scope, StatisticsRange range, String tenantId) {
        StatisticsRepository repository = new StatisticsRepository(manager);
        List<TenantView> tenants = repository.tenants(tenantId);
        Map<String, List<DailyStatistics>> rows = repository.daily(range, tenantId);
        Map<String, LegacyUnknownTime> unknown = repository.legacy(tenantId);
        List<HospitalStatisticsView> hospitals = new ArrayList<HospitalStatisticsView>();
        Map<String, StatisticsMetrics> daily = new LinkedHashMap<String, StatisticsMetrics>();
        for (LocalDate date = range.getFrom(); !date.isAfter(range.getTo()); date = date.plusDays(1))
            daily.put(date.toString(), StatisticsMetrics.zero());
        StatisticsMetrics total = StatisticsMetrics.zero();
        LegacyUnknownTime legacy = LegacyUnknownTime.zero();
        long active = 0;
        long disabled = 0;
        for (TenantView tenant : tenants) {
            StatisticsMetrics metrics = StatisticsMetrics.zero();
            List<DailyStatistics> days = rows.get(tenant.getId());
            if (days != null) {
                for (DailyStatistics day : days) {
                    metrics = metrics.plus(day.getMetrics());
                    daily.put(day.getDate(), daily.get(day.getDate()).plus(day.getMetrics()));
                }
            }
            LegacyUnknownTime quality = unknown.get(tenant.getId());
            if (quality == null) quality = LegacyUnknownTime.zero();
            hospitals.add(new HospitalStatisticsView(tenant, metrics, quality));
            total = total.plus(metrics);
            legacy = legacy.plus(quality);
            if ("ACTIVE".equals(tenant.getStatus())) active++;
            if ("DISABLED".equals(tenant.getStatus())) disabled++;
        }
        List<DailyStatistics> trend = new ArrayList<DailyStatistics>();
        for (Map.Entry<String, StatisticsMetrics> day : daily.entrySet())
            trend.add(new DailyStatistics(day.getKey(), day.getValue()));
        TenantCounts counts = tenantId == null ? new TenantCounts(tenants.size(), active, disabled) : null;
        return new StatisticsReport(scope, range.getFrom().toString(), range.getTo().toString(),
                StatisticsRange.ZONE.getId(), DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(clock.instant().atZone(StatisticsRange.ZONE)),
                total, trend, hospitals, legacy, counts);
    }
}
