package com.medcloud.platform.audit.application;

import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditEvent;
import com.medcloud.platform.audit.domain.AuditResult;
import com.medcloud.platform.audit.infrastructure.AuditEventRepository;
import com.medcloud.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class AuditService {
    private static final Set<String> OBJECT_TYPES = Set.of(
            "TENANT", "USER_ACCOUNT", "DEPARTMENT", "DOCTOR", "SCHEDULE_SLOT",
            "APPOINTMENT", "CONSULTATION", "MEDICAL_ORDER", "FILE");

    private final AuditEventRepository events;

    public AuditService(AuditEventRepository events) {
        this.events = events;
    }

    /**
     * Persists an auditable mutation outcome without accepting any request payload or clinical content.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            CurrentUser actor,
            AuditAction action,
            String objectType,
            UUID objectId,
            AuditResult result,
            String requestId
    ) {
        if (action == null || result == null || !OBJECT_TYPES.contains(objectType)) {
            throw new IllegalArgumentException("Audit events require an enumerated action, result and object type");
        }
        events.save(AuditEvent.create(
                actor == null ? null : actor.userId(),
                actor == null ? null : actor.tenantId(),
                action,
                objectType,
                objectId,
                result,
                safeRequestId(requestId)));
    }

    private static String safeRequestId(String requestId) {
        if (requestId == null) {
            return "unknown";
        }
        try {
            return UUID.fromString(requestId).toString();
        } catch (IllegalArgumentException ignored) {
            return "unknown";
        }
    }
}
