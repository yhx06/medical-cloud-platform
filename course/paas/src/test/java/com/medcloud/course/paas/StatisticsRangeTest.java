package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.statistics.StatisticsRange;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsRangeTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test void defaultsAndWhitespaceUseThirtyShanghaiDaysEvenWithDifferentClockZone() {
        StatisticsRange range = StatisticsRange.parse(" ", null, CLOCK.withZone(ZoneId.of("America/Los_Angeles")));
        assertEquals(LocalDate.parse("2026-09-04"), range.getFrom());
        assertEquals(LocalDate.parse("2026-10-03"), range.getTo());
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Clock midnight = Clock.fixed(Instant.parse("2026-10-02T16:01:00Z"), ZoneId.of("America/Los_Angeles"));
            assertEquals(LocalDate.parse("2026-10-03"), StatisticsRange.parse(null, "", midnight).getTo());
            range = StatisticsRange.parse(" 2024-02-29 ", "2024-02-29", CLOCK);
            assertEquals(Instant.parse("2024-02-28T16:00:00Z"), range.getStart());
            assertEquals(Instant.parse("2024-02-29T16:00:00Z"), range.getEndExclusive());
        } finally { TimeZone.setDefault(previous); }
    }

    @Test void accepts366DaysAndMysqlBoundsButRejectsInvalidOrUnrepresentableDates() {
        assertEquals(LocalDate.parse("2024-12-31"), StatisticsRange.parse("2024-01-01", "2024-12-31", CLOCK).getTo());
        assertEquals(LocalDate.parse("1000-01-01"), StatisticsRange.parse("1000-01-01", "1000-01-01", CLOCK).getFrom());
        assertEquals(LocalDate.parse("9999-12-30"), StatisticsRange.parse("9999-12-30", "9999-12-30", CLOCK).getTo());
        for (String[] pair : new String[][] {
            {"2024-01-01", "2025-01-01"}, {"2026-10-03", null}, {null, "2026-10-03"},
            {"2026-10-04", "2026-10-03"}, {"2026-2-01", "2026-02-02"},
            {"2026-02-29", "2026-03-01"}, {"2024-02-30", "2024-03-01"},
            {"10000-01-01", "10000-01-02"}, {"0999-12-31", "1000-01-01"},
            {"9999-12-31", "9999-12-31"}, {"+2026-10-03", "2026-10-03"}
        }) assertThrows(IllegalArgumentException.class, () -> StatisticsRange.parse(pair[0], pair[1], CLOCK), java.util.Arrays.toString(pair));
    }

    @Test void metricAndUnknownTimeAdditionPreserveInputsAndExactDecimalMoney() {
        StatisticsMetrics a = new StatisticsMetrics(1,1,0,0,0,2,3,4,5,new BigDecimal("0.10"),new BigDecimal("0.20"));
        StatisticsMetrics b = new StatisticsMetrics(2,0,1,1,0,3,4,5,6,new BigDecimal("0.20"),new BigDecimal("0.10"));
        StatisticsMetrics sum = a.plus(b);
        assertEquals(3, sum.getAppointmentTotal());
        assertEquals(5, sum.getConsultationStarted());
        assertEquals(7, sum.getConsultationCompleted());
        assertEquals(9, sum.getOrderGenerated());
        assertEquals(11, sum.getPaidOrderCount());
        assertEquals(new BigDecimal("0.30"), sum.getOrderAmount());
        assertEquals(new BigDecimal("0.30"), sum.getPaidRevenue());
        assertEquals(new BigDecimal("0.10"), a.getOrderAmount());
        assertEquals(new BigDecimal("0.20"), b.getOrderAmount());
        assertEquals(new BigDecimal("0.00"), StatisticsMetrics.zero().getPaidRevenue());
        LegacyUnknownTime unknown = new LegacyUnknownTime(1,2,3,4,new BigDecimal("0.10"),new BigDecimal("0.20"));
        LegacyUnknownTime combined = unknown.plus(new LegacyUnknownTime(4,3,2,1,new BigDecimal("0.20"),new BigDecimal("0.10")));
        assertEquals(5, combined.getStartedAppointments());
        assertEquals(5, combined.getCompletedRecords());
        assertEquals(5, combined.getGeneratedOrders());
        assertEquals(5, combined.getPaidOrders());
        assertEquals(new BigDecimal("0.30"), combined.getOrderAmount());
        assertEquals(new BigDecimal("0.30"), combined.getPaidRevenue());
        assertEquals(1, unknown.getStartedAppointments());
        assertEquals(new BigDecimal("0.00"), LegacyUnknownTime.zero().getOrderAmount());
    }
}
