package com.medcloud.course.contract;

import java.math.BigDecimal;

/** Immutable aggregate value; money is expressed in yuan with two decimal places. */
public final class StatisticsMetrics {
    private final long appointmentTotal;
    private final long appointmentBooked;
    private final long appointmentInProgress;
    private final long appointmentCanceled;
    private final long appointmentCompleted;
    private final long consultationStarted;
    private final long consultationCompleted;
    private final long orderGenerated;
    private final long paidOrderCount;
    private final BigDecimal orderAmount;
    private final BigDecimal paidRevenue;

    public StatisticsMetrics(long appointmentTotal, long appointmentBooked, long appointmentInProgress, long appointmentCanceled, long appointmentCompleted, long consultationStarted, long consultationCompleted, long orderGenerated, long paidOrderCount,
            BigDecimal orderAmount, BigDecimal paidRevenue) {
        this.appointmentTotal = appointmentTotal;
        this.appointmentBooked = appointmentBooked;
        this.appointmentInProgress = appointmentInProgress;
        this.appointmentCanceled = appointmentCanceled;
        this.appointmentCompleted = appointmentCompleted;
        this.consultationStarted = consultationStarted;
        this.consultationCompleted = consultationCompleted;
        this.orderGenerated = orderGenerated;
        this.paidOrderCount = paidOrderCount;
        this.orderAmount = money(orderAmount);
        this.paidRevenue = money(paidRevenue);
    }

    public static StatisticsMetrics zero() {
        return new StatisticsMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public StatisticsMetrics plus(StatisticsMetrics other) {
        return new StatisticsMetrics(appointmentTotal + other.appointmentTotal, appointmentBooked + other.appointmentBooked, appointmentInProgress + other.appointmentInProgress, appointmentCanceled + other.appointmentCanceled, appointmentCompleted + other.appointmentCompleted, consultationStarted + other.consultationStarted, consultationCompleted + other.consultationCompleted, orderGenerated + other.orderGenerated, paidOrderCount + other.paidOrderCount,
                orderAmount.add(other.orderAmount), paidRevenue.add(other.paidRevenue));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2);
    }

    public long getAppointmentTotal() { return appointmentTotal; }
    public long getAppointmentBooked() { return appointmentBooked; }
    public long getAppointmentInProgress() { return appointmentInProgress; }
    public long getAppointmentCanceled() { return appointmentCanceled; }
    public long getAppointmentCompleted() { return appointmentCompleted; }
    public long getConsultationStarted() { return consultationStarted; }
    public long getConsultationCompleted() { return consultationCompleted; }
    public long getOrderGenerated() { return orderGenerated; }
    public long getPaidOrderCount() { return paidOrderCount; }
    public BigDecimal getOrderAmount() { return orderAmount; }
    public BigDecimal getPaidRevenue() { return paidRevenue; }
}
