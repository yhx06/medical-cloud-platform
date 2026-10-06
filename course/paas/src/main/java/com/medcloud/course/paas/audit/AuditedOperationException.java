package com.medcloud.course.paas.audit;

import javax.ejb.ApplicationException;
import java.util.Objects;

@ApplicationException(rollback = false)
public class AuditedOperationException extends RuntimeException {
    private final String requestId;
    public AuditedOperationException(RuntimeException originalFailure, String requestId) {
        super("Audited operation failed", Objects.requireNonNull(originalFailure));
        this.requestId = AuditContext.requireRequestId(requestId);
    }
    public String getRequestId() { return requestId; }
}
