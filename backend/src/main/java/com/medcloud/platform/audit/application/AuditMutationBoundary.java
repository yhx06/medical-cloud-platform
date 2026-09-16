package com.medcloud.platform.audit.application;

import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditObjectType;
import com.medcloud.platform.audit.domain.AuditResult;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Explicit controller-side mutation boundary. It deliberately receives identifiers only and is
 * not a request interceptor, so request bodies and sensitive clinical data never reach auditing.
 */
@Component
public class AuditMutationBoundary {
    private static final Logger log = LoggerFactory.getLogger(AuditMutationBoundary.class);

    private final AuditService audit;

    public AuditMutationBoundary(AuditService audit) {
        this.audit = audit;
    }

    public <T> T execute(
            CurrentUser actor,
            AuditAction action,
            AuditObjectType objectType,
            UUID rejectedObjectId,
            UUID requestId,
            Supplier<T> mutation,
            Function<T, UUID> successfulObjectId
    ) {
        try {
            T result = mutation.get();
            recordOutcome(actor, action, objectType, successfulObjectId.apply(result),
                    AuditResult.SUCCESS, requestId);
            return result;
        } catch (BusinessException exception) {
            recordOutcome(actor, action, objectType, rejectedObjectId,
                    AuditResult.REJECTED, requestId);
            throw exception;
        }
    }

    public void recordOutcome(
            CurrentUser actor,
            AuditAction action,
            AuditObjectType objectType,
            UUID objectId,
            AuditResult result,
            UUID requestId
    ) {
        try {
            audit.record(actor, action, objectType, objectId, result, requestId);
        } catch (RuntimeException exception) {
            log.warn("Audit persistence failed for action {}", action);
        }
    }
}
