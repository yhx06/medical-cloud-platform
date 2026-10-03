package com.medcloud.course.contract;

public final class DailyStatistics {
    private final String date;
    private final StatisticsMetrics metrics;

    public DailyStatistics(String date, StatisticsMetrics metrics) {
        this.date = date;
        this.metrics = metrics;
    }

    public String getDate() { return date; }
    public StatisticsMetrics getMetrics() { return metrics; }
}
