package com.medcloud.platform.audit.application;

import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditEvent;
import com.medcloud.platform.audit.domain.AuditObjectType;
import com.medcloud.platform.audit.domain.AuditResult;
import com.medcloud.platform.audit.infrastructure.AuditEventRepository;
import com.medcloud.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {
    private final AuditEventRepository events;

    public AuditService(AuditEventRepository events) {
        this.events = events;
    }

    /**
     * Persists an auditable mutation outcome without accepting any request payload or clinical content.
     */
    @Transactional
    public void record(
            CurrentUser actor,
            AuditAction action,
            AuditObjectType objectType,
            UUID objectId,
            AuditResult result,
            UUID requestId
    ) {
        events.save(AuditEvent.create(
                actor == null ? null : actor.userId(),
                actor == null ? null : actor.tenantId(),
                action,
                objectType,
                objectId,
                result,
                requestId));
    }
}
