package com.medcloud.platform.statistics.application;

import java.time.LocalDate;
import java.util.UUID;

public record StatisticsQuery(UUID actorId, UUID tenantId, LocalDate from, LocalDate to) {
}
