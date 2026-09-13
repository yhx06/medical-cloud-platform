package com.medcloud.platform.consultation.domain;

import java.util.UUID;

public record ConsultationCommand(UUID actorId, UUID tenantId, UUID appointmentId, String summary) {
}
