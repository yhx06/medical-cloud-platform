package com.medcloud.platform.consultation.application;

import java.math.BigDecimal;
import java.util.UUID;

public record ConsultationCompletedEvent(
        UUID consultationId,
        UUID appointmentId,
        UUID patientId,
        UUID tenantId,
        BigDecimal fee,
        UUID doctorId
) {
    public ConsultationCompletedEvent(
            UUID consultationId,
            UUID appointmentId,
            UUID patientId,
            UUID tenantId,
            BigDecimal fee
    ) {
        this(consultationId, appointmentId, patientId, tenantId, fee, null);
    }
}
