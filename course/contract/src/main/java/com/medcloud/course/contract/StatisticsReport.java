package com.medcloud.course.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StatisticsReport {
    private final String scope;
    private final String from;
    private final String to;
    private final String timezone;
    private final String generatedAt;
    private final StatisticsMetrics metrics;
    private final List<DailyStatistics> daily;
    private final List<HospitalStatisticsView> hospitals;
    private final LegacyUnknownTime legacyUnknownTime;
    private final TenantCounts tenantCounts;

    public StatisticsReport(String scope, String from, String to, String timezone, String generatedAt,
            StatisticsMetrics metrics, List<DailyStatistics> daily, List<HospitalStatisticsView> hospitals,
            LegacyUnknownTime legacyUnknownTime, TenantCounts tenantCounts) {
        this.scope = scope;
        this.from = from;
        this.to = to;
        this.timezone = timezone;
        this.generatedAt = generatedAt;
        this.metrics = metrics;
        this.daily = Collections.unmodifiableList(new ArrayList<DailyStatistics>(daily));
        this.hospitals = Collections.unmodifiableList(new ArrayList<HospitalStatisticsView>(hospitals));
        this.legacyUnknownTime = legacyUnknownTime;
        this.tenantCounts = tenantCounts;
    }

    public String getScope() { return scope; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getTimezone() { return timezone; }
    public String getGeneratedAt() { return generatedAt; }
    public StatisticsMetrics getMetrics() { return metrics; }
    public List<DailyStatistics> getDaily() { return daily; }
    public List<HospitalStatisticsView> getHospitals() { return hospitals; }
    public LegacyUnknownTime getLegacyUnknownTime() { return legacyUnknownTime; }
    public TenantCounts getTenantCounts() { return tenantCounts; }
}
