package com.medcloud.course.paas;

import com.medcloud.course.paas.order.PaymentReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentReferenceTest {
    @Test
    void referenceFollowsTheFixedShape() {
        String reference = PaymentReference.generate(Instant.parse("2030-05-21T01:00:00Z"));
        assertTrue(reference.matches("PAY-20300521-[0-9A-F]{12}"), "unexpected shape: " + reference);
    }

    @Test
    void distinctPaymentsDoNotCollide() {
        String first = PaymentReference.generate(Instant.parse("2030-05-21T01:00:00Z"));
        String second = PaymentReference.generate(Instant.parse("2030-05-21T01:00:01Z"));
        assertNotEquals(first, second);
    }

    @Test
    void nullTimeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PaymentReference.generate(null));
    }

    @Test
    void maskedFormHidesTheMiddleOfTheSuffix() {
        String full = PaymentReference.generate(Instant.parse("2030-05-21T01:00:00Z"));
        String masked = PaymentReference.masked(full);
        assertTrue(masked.startsWith("PAY-20300521-"), "date prefix lost: " + masked);
        assertTrue(masked.endsWith(full.substring(full.length() - 4)), "suffix tail lost: " + masked);
        assertTrue(masked.contains("\u2022\u2022\u2022\u2022"), "masking marker missing: " + masked);
        // The masked form never exposes the full 12-hex suffix.
        assertTrue(masked.length() <= full.length() + 4, "masked form grew unexpectedly: " + masked);
        assertTrue(!masked.contains(full.substring(full.lastIndexOf('-') + 1)), "full suffix leaked: " + masked);
    }

    @Test
    void nullReferenceMasksToNull() {
        assertNull(PaymentReference.masked(null));
    }
}
