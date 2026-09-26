package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiError;
import com.medcloud.course.paas.auth.AccessDeniedException;

import javax.ejb.EJBException;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
public class ApiFailureMapper implements ExceptionMapper<RuntimeException> {
    private static final Logger LOG = Logger.getLogger(ApiFailureMapper.class.getName());
    @Override
    public Response toResponse(RuntimeException failure) {
        while (failure instanceof EJBException && failure.getCause() instanceof RuntimeException
                && failure.getCause() != failure) {
            failure = (RuntimeException) failure.getCause();
        }
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
        } else if (failure instanceof IllegalStateException) {
            status = 409;
            code = "CONFLICT";
            message = "Operation conflicts with current state";
        } else {
            status = 500;
            code = "INTERNAL_ERROR";
            message = "Service temporarily unavailable";
            LOG.log(Level.SEVERE, "Unhandled API failure", failure);
        }
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(code, message, UUID.randomUUID().toString())).build();
    }
}
