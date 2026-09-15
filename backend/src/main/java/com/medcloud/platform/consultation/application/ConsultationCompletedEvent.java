package com.medcloud.platform.consultation.application;

import java.math.BigDecimal;
import java.util.UUID;

public record ConsultationCompletedEvent(
        UUID consultationId,
        UUID appointmentId,
        UUID patientId,
        UUID tenantId,
        BigDecimal fee
) {
}
