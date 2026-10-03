package com.medcloud.course.paas.statistics;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Inclusive calendar dates and their exclusive Shanghai event-time bounds. */
public final class StatisticsRange {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final LocalDate from;
    private final LocalDate to;

    private StatisticsRange(LocalDate from, LocalDate to) { this.from = from; this.to = to; }

    public static StatisticsRange parse(String from, String to, Clock clock) {
        String first = normalize(from);
        String last = normalize(to);
        LocalDate start;
        LocalDate end;
        if (first == null && last == null) {
            end = LocalDate.now(clock.withZone(ZONE));
            start = end.minusDays(29);
        } else {
            if (first == null || last == null) throw new IllegalArgumentException("both statistics dates required");
            start = date(first);
            end = date(last);
        }
        if (start.getYear() < 1000 || end.getYear() > 9999
                || end.equals(LocalDate.of(9999, 12, 31)) || start.isAfter(end)
                || ChronoUnit.DAYS.between(start, end) >= 366)
            throw new IllegalArgumentException("invalid statistics date range");
        return new StatisticsRange(start, end);
    }

    private static String normalize(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static LocalDate date(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("invalid statistics date");
        try { return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (DateTimeException invalid) { throw new IllegalArgumentException("invalid statistics date", invalid); }
    }

    public LocalDate getFrom() { return from; }
    public LocalDate getTo() { return to; }
    public Instant getStart() { return from.atStartOfDay(ZONE).toInstant(); }
    public Instant getEndExclusive() { return to.plusDays(1).atStartOfDay(ZONE).toInstant(); }
}
