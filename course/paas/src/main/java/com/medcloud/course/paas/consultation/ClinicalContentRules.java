package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.ClinicalContentView;
import java.util.UUID;

public final class ClinicalContentRules {
    private ClinicalContentRules() { }

    public static String requireDescription(String value) {
        return text(value, 500);
    }

    public static ClinicalContentView requireDraft(ClinicalContentView content) {
        if (content == null) throw invalid();
        return new ClinicalContentView(text(content.getComplaint(), 500), text(content.getDiagnosis(), 500),
                text(content.getAdvice(), 2000), text(content.getNotes(), 2000));
    }

    public static ClinicalContentView requireFinal(ClinicalContentView content) {
        ClinicalContentView normalized = requireDraft(content);
        if (normalized.getDiagnosis() == null || normalized.getAdvice() == null) throw invalid();
        return normalized;
    }

    public static String requireReason(String value) {
        String normalized = text(value, 2000);
        if (normalized == null) throw invalid();
        return normalized;
    }

    public static long requireRevision(Long value) {
        if (value == null || value < 0) throw invalid();
        return value;
    }

    public static String requireOperationKey(String value) {
        if (value == null || value.length() != 36) throw invalid();
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw invalid();
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
        return value;
    }

    private static String text(String value, int limit) {
        if (value == null) return null;
        // Validate before trimming so control characters cannot disappear silently.
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == 0) throw invalid();
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw invalid();
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw invalid();
            }
        }
        String normalized = value.trim();
        if (normalized.codePointCount(0, normalized.length()) > limit) throw invalid();
        return normalized.isEmpty() ? null : normalized;
    }

    private static ClinicalSupplementException invalid() {
        return new ClinicalSupplementException(400, "CLINICAL_INPUT_INVALID");
    }
}
