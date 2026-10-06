package com.medcloud.course.paas.audit;

import com.medcloud.course.paas.api.ApiFailureMapper;
import javax.ejb.*;
import java.time.Instant;
import java.util.Objects;
import java.util.logging.Logger;

/** Auth and one business EJB call only; response assembly belongs after execute returns. */
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class AuditOperationRunner {
    private static final Logger LOG = Logger.getLogger(AuditOperationRunner.class.getName());
    @EJB private AuditFailureJournal failures;
    public AuditOperationRunner() {}
    public AuditOperationRunner(AuditFailureJournal failures) { this.failures = Objects.requireNonNull(failures); }
    @FunctionalInterface
    public interface AuditAction<T> { T execute(AuditContext context); }
    public <T> T execute(AuditContext context, AuditAction<T> action) {
        Objects.requireNonNull(context); Objects.requireNonNull(action);
        try { return action.execute(context); }
        catch (RuntimeException failure) {
            // Business proxy returned: its transaction has already ended before the independent failure call.
            try { failures.append(context, ApiFailureMapper.reasonFor(failure), Instant.now()); }
            catch (RuntimeException unavailable) { LOG.warning("requestId=" + context.getRequestId() + " type=AUDIT_WRITE_FAILED"); }
            throw new AuditedOperationException(failure, context.getRequestId());
        }
    }
}
