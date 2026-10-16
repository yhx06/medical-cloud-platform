package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.ClinicalContentView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClinicalContentRulesTest {
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(value); return result.toString();
    }
    private void invalid(Runnable action) {
        ClinicalSupplementException error = assertThrows(ClinicalSupplementException.class, action::run);
        assertEquals(400, error.getStatus()); assertEquals("CLINICAL_INPUT_INVALID", error.getCode());
        assertNull(error.getCause()); assertFalse(error.getMessage().contains("secret"));
    }
    @Test void unicodeCodePointsBoundary() {
        String emoji500 = repeat("\uD83D\uDE00", 500);
        assertEquals(emoji500, ClinicalContentRules.requireDescription(emoji500));
        invalid(() -> ClinicalContentRules.requireDescription(repeat("中", 501)));
        invalid(() -> ClinicalContentRules.requireDescription(repeat("\uD83D\uDE00", 501)));
        assertEquals(emoji500, ClinicalContentRules.requireFinal(new ClinicalContentView(emoji500, emoji500, repeat("a", 2000), repeat("n", 2000))).getDiagnosis());
        invalid(() -> ClinicalContentRules.requireDraft(new ClinicalContentView(repeat("c", 501), null, null, null)));
        invalid(() -> ClinicalContentRules.requireDraft(new ClinicalContentView(null, repeat("d", 501), null, null)));
        invalid(() -> ClinicalContentRules.requireDraft(new ClinicalContentView(null, null, repeat("a", 2001), null)));
        invalid(() -> ClinicalContentRules.requireDraft(new ClinicalContentView(null, null, null, repeat("n", 2001))));
        assertEquals(repeat("\uD83D\uDE00", 2000), ClinicalContentRules.requireReason(repeat("\uD83D\uDE00", 2000)));
        invalid(() -> ClinicalContentRules.requireReason(repeat("r", 2001)));
    }
    @Test void rejectsNulAndUnpairedSurrogates() {
        for (String bad : new String[] {"secret\u0000", "\uD83D", "\uDE00", "\uD83Dx"}) {
            invalid(() -> ClinicalContentRules.requireDescription(bad));
            invalid(() -> ClinicalContentRules.requireDraft(new ClinicalContentView(null, bad, null, null)));
            invalid(() -> ClinicalContentRules.requireDraft(new ClinicalContentView(null, null, bad, null)));
            invalid(() -> ClinicalContentRules.requireDraft(new ClinicalContentView(null, null, null, bad)));
            invalid(() -> ClinicalContentRules.requireReason(bad));
        }
        assertEquals("line1\nline2", ClinicalContentRules.requireDescription("line1\nline2"));
    }
    @Test void emptyDraftButNotEmptyFinal() {
        ClinicalContentView draft = ClinicalContentRules.requireDraft(new ClinicalContentView(null, " ", "", "\t"));
        assertNull(draft.getComplaint()); assertNull(draft.getDiagnosis()); assertNull(draft.getAdvice()); assertNull(draft.getNotes());
        assertNull(ClinicalContentRules.requireDescription(" "));
        assertNull(ClinicalContentRules.requireDescription(null));
        invalid(() -> ClinicalContentRules.requireDraft(null));
        invalid(() -> ClinicalContentRules.requireFinal(null));
        invalid(() -> ClinicalContentRules.requireFinal(draft));
        invalid(() -> ClinicalContentRules.requireFinal(new ClinicalContentView(null, "", "a", null)));
        invalid(() -> ClinicalContentRules.requireFinal(new ClinicalContentView(null, "d", null, null)));
        ClinicalContentView finalValue = ClinicalContentRules.requireFinal(new ClinicalContentView(" c ", " d ", " a ", " n "));
        assertEquals("c", finalValue.getComplaint()); assertEquals("d", finalValue.getDiagnosis());
        assertEquals("a", finalValue.getAdvice()); assertEquals("n", finalValue.getNotes());
        assertEquals("reason", ClinicalContentRules.requireReason(" reason "));
        invalid(() -> ClinicalContentRules.requireReason(null)); invalid(() -> ClinicalContentRules.requireReason(" "));
    }
    @Test void versionMissingNegativeRejected() {
        invalid(() -> ClinicalContentRules.requireRevision(null)); invalid(() -> ClinicalContentRules.requireRevision(-1L));
        assertEquals(0, ClinicalContentRules.requireRevision(0L));
        assertEquals(Long.MAX_VALUE, ClinicalContentRules.requireRevision(Long.MAX_VALUE));
    }
    @Test void uuidKeyCanonical() {
        String key = "123e4567-e89b-12d3-a456-426614174000";
        assertEquals(key, ClinicalContentRules.requireOperationKey(key));
        invalid(() -> ClinicalContentRules.requireOperationKey(null));
        invalid(() -> ClinicalContentRules.requireOperationKey("1-1-1-1-1"));
        invalid(() -> ClinicalContentRules.requireOperationKey(key.toUpperCase()));
        invalid(() -> ClinicalContentRules.requireOperationKey(" " + key));
        invalid(() -> ClinicalContentRules.requireOperationKey("secret"));
    }
    @Test void errorsAreRestrictedAndSafe() {
        for (String code : new String[] {"CLINICAL_STATE_CONFLICT", "CLINICAL_REVISION_CONFLICT", "CLINICAL_OPERATION_CONFLICT"}) {
            ClinicalSupplementException error = new ClinicalSupplementException(409, code);
            assertEquals(409, error.getStatus()); assertEquals(code, error.getCode()); assertNull(error.getCause());
            assertEquals(new ClinicalSupplementException(400, "CLINICAL_INPUT_INVALID").getMessage(), error.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> new ClinicalSupplementException(500, "secret"));
        assertThrows(IllegalArgumentException.class, () -> new ClinicalSupplementException(409, "CLINICAL_INPUT_INVALID"));
        assertThrows(IllegalArgumentException.class, () -> new ClinicalSupplementException(400, "CLINICAL_STATE_CONFLICT"));
    }
}
