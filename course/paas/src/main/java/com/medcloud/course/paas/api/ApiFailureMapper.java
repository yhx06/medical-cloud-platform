package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiError;
import com.medcloud.course.contract.AuditReasonCode;
import com.medcloud.course.paas.audit.AuditInfrastructureException;
import com.medcloud.course.paas.audit.AuditedOperationException;
import com.medcloud.course.paas.auth.AccessDeniedException;

import javax.ejb.EJBException;
import javax.persistence.LockTimeoutException;
import javax.persistence.PessimisticLockException;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Context;
import org.jboss.resteasy.spi.HttpRequest;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.util.UUID;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
public class ApiFailureMapper implements ExceptionMapper<RuntimeException> {
    private static final Logger LOG = Logger.getLogger(ApiFailureMapper.class.getName());
    @Context private HttpRequest request;

    public static RuntimeException unwrap(RuntimeException failure) {
        Set<RuntimeException> seen = Collections.newSetFromMap(new IdentityHashMap<RuntimeException, Boolean>());
        while ((failure instanceof EJBException || failure instanceof AuditedOperationException)
                && failure.getCause() instanceof RuntimeException && seen.add(failure)
                && !seen.contains(failure.getCause())) {
            failure = (RuntimeException) failure.getCause();
        }
        return failure;
    }

    public static AuditReasonCode reasonFor(RuntimeException failure) {
        failure = unwrap(failure);
        if (failure instanceof AuditInfrastructureException) return AuditReasonCode.INTERNAL_ERROR;
        if (failure instanceof AccessDeniedException) return AuditReasonCode.FORBIDDEN;
        if (failure instanceof SecurityException) return AuditReasonCode.UNAUTHORIZED;
        if (failure instanceof IllegalArgumentException) return AuditReasonCode.BAD_REQUEST;
        if (failure instanceof IllegalStateException || failure instanceof LockTimeoutException
                || failure instanceof PessimisticLockException) return AuditReasonCode.CONFLICT;
        return AuditReasonCode.INTERNAL_ERROR;
    }

    private String auditedRequestId(RuntimeException failure) {
        String serverId = AuditRequestIds.get(request);
        if (serverId != null) return serverId;
        Set<RuntimeException> seen = Collections.newSetFromMap(new IdentityHashMap<RuntimeException, Boolean>());
        while (seen.add(failure)) {
            if (failure instanceof AuditedOperationException) return ((AuditedOperationException) failure).getRequestId();
            if (!(failure instanceof EJBException) || !(failure.getCause() instanceof RuntimeException)) break;
            failure = (RuntimeException) failure.getCause();
        }
        return null;
    }
    @Override
    public Response toResponse(RuntimeException failure) {
        String trackedId = auditedRequestId(failure);
        String requestId = trackedId == null ? UUID.randomUUID().toString() : trackedId;
        failure = unwrap(failure);
        int status;
        String code;
        String message;
        if (failure instanceof NotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
            message = "JSON content type required";
        } else if (failure instanceof AccessDeniedException) {
            status = 403;
            code = "FORBIDDEN";
            message = "Access denied";
        } else if (failure instanceof SecurityException) {
            status = 401;
            code = "UNAUTHORIZED";
            message = "Authentication required or account inactive";
        } else if (failure instanceof IllegalArgumentException) {
            status = 400;
            code = "BAD_REQUEST";
            message = "Invalid request";
        } else if (!(failure instanceof AuditInfrastructureException) && (failure instanceof IllegalStateException || failure instanceof LockTimeoutException
                || failure instanceof PessimisticLockException)) {
            status = 409;
            code = "CONFLICT";
            message = "Operation conflicts with current state";
        } else {
            status = 500;
            code = "INTERNAL_ERROR";
            message = "Service temporarily unavailable";
            LOG.log(Level.SEVERE, "requestId=" + requestId + " type=INTERNAL_ERROR");
        }
        Response.ResponseBuilder response = Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(code, message, requestId));
        if (trackedId != null) response.header("X-Request-ID", trackedId);
        return response.build();
    }
}
