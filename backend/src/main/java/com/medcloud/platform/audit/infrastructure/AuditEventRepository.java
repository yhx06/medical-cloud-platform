package com.medcloud.platform.audit.infrastructure;

import com.medcloud.platform.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
}
