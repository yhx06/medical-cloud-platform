package com.medcloud.course.paas.order;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * A unique, non-sequential simulated payment reference (never a secret or payment credential) and
 * its masked presentation form. The full 12-hex suffix stays in the database; the view only exposes
 * the date and both ends of the suffix.
 */
public final class PaymentReference {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private PaymentReference() { }

    public static String generate(Instant now) {
        if (now == null) throw new IllegalArgumentException("payment time required");
        String date = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().format(BASIC_DATE);
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return "PAY-" + date + "-" + hex;
    }

    public static String masked(String full) {
        if (full == null) return null;
        int dash = full.lastIndexOf('-');
        if (dash < 0 || full.length() - dash - 1 != 12) return full;
        String suffix = full.substring(dash + 1);
        return full.substring(0, dash + 1) + suffix.substring(0, 4) + "\u2022\u2022\u2022\u2022" + suffix.substring(8);
    }
}
