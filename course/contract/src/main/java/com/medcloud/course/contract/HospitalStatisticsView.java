package com.medcloud.course.contract;

public final class HospitalStatisticsView {
    private final TenantView tenant;
    private final StatisticsMetrics metrics;
    private final LegacyUnknownTime legacyUnknownTime;

    public HospitalStatisticsView(TenantView tenant, StatisticsMetrics metrics, LegacyUnknownTime legacyUnknownTime) {
        this.tenant = tenant;
        this.metrics = metrics;
        this.legacyUnknownTime = legacyUnknownTime;
    }

    public TenantView getTenant() { return tenant; }
    public StatisticsMetrics getMetrics() { return metrics; }
    public LegacyUnknownTime getLegacyUnknownTime() { return legacyUnknownTime; }
}
