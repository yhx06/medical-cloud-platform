package com.medcloud.course.contract;

import java.math.BigDecimal;

/** Immutable aggregate value; money is expressed in yuan with two decimal places. */
public final class LegacyUnknownTime {
    private final long startedAppointments;
    private final long completedRecords;
    private final long generatedOrders;
    private final long paidOrders;
    private final BigDecimal orderAmount;
    private final BigDecimal paidRevenue;

    public LegacyUnknownTime(long startedAppointments, long completedRecords, long generatedOrders, long paidOrders,
            BigDecimal orderAmount, BigDecimal paidRevenue) {
        this.startedAppointments = startedAppointments;
        this.completedRecords = completedRecords;
        this.generatedOrders = generatedOrders;
        this.paidOrders = paidOrders;
        this.orderAmount = money(orderAmount);
        this.paidRevenue = money(paidRevenue);
    }

    public static LegacyUnknownTime zero() {
        return new LegacyUnknownTime(0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public LegacyUnknownTime plus(LegacyUnknownTime other) {
        return new LegacyUnknownTime(startedAppointments + other.startedAppointments, completedRecords + other.completedRecords, generatedOrders + other.generatedOrders, paidOrders + other.paidOrders,
                orderAmount.add(other.orderAmount), paidRevenue.add(other.paidRevenue));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2);
    }

    public long getStartedAppointments() { return startedAppointments; }
    public long getCompletedRecords() { return completedRecords; }
    public long getGeneratedOrders() { return generatedOrders; }
    public long getPaidOrders() { return paidOrders; }
    public BigDecimal getOrderAmount() { return orderAmount; }
    public BigDecimal getPaidRevenue() { return paidRevenue; }
}
