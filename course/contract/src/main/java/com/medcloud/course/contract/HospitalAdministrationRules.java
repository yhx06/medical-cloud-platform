package com.medcloud.course.contract;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class HospitalAdministrationRules {
    private HospitalAdministrationRules() { }

    public static String requireHospitalName(String value) {
        requireText(value);
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        String name = value.substring(start, end);
        if (name.isEmpty() || name.length() > 100) throw invalid();
        return name;
    }

    public static String requireUsername(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{3,64}")) throw invalid();
        return value;
    }

    public static String usernameKey(String value) {
        return requireUsername(value).toLowerCase(Locale.ROOT);
    }

    public static String requireInitialPassword(String value) {
        requireText(value);
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 8 || bytes > 72) throw invalid();
        return value;
    }

    public static String requireOperationKey(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || "00000000-0000-0000-0000-000000000000".equals(value)) throw invalid();
        return value;
    }

    /** Optional free text (title/bio): null or blank becomes null; otherwise trimmed, bounded, control-free. */
    public static String requireOptionalText(String value, int maxLength) {
        if (value == null) return null;
        requireText(value);
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        String text = value.substring(start, end);
        if (text.isEmpty()) return null;
        if (text.length() > maxLength) throw invalid();
        return text;
    }

    public static String requireTargetId(String value) {
        requireText(value);
        if (value.isEmpty() || value.length() > 36) throw invalid();
        return value;
    }

    public static String requireStatus(String value) {
        if (!"ACTIVE".equals(value) && !"DISABLED".equals(value)) throw invalid();
        return value;
    }

    public static int requirePage(String raw, int fallback, int max) {
        if (fallback < 1 || fallback > max) throw invalid();
        if (raw == null || raw.isEmpty()) return fallback;
        if (!raw.matches("[0-9]+")) throw invalid();
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            throw invalid();
        }
        if (value < 1 || value > max) throw invalid();
        return value;
    }

    public static int requireOffset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw invalid();
        long offset = ((long) page - 1L) * size;
        if (offset > Integer.MAX_VALUE) throw invalid();
        return (int) offset;
    }

    private static void requireText(String value) {
        if (value == null) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isISOControl(current)) throw invalid();
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw invalid();
                i++;
            } else if (Character.isLowSurrogate(current)) {
                throw invalid();
            }
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid hospital administration input");
    }
}
