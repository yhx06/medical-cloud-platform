package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiError;
import com.medcloud.course.paas.auth.AccessDeniedException;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.util.UUID;

@Provider
public class ApiFailureMapper implements ExceptionMapper<RuntimeException> {
    @Override
    public Response toResponse(RuntimeException failure) {
        int status;
        String code;
        String message;
        if (failure instanceof AccessDeniedException) {
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
        }
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(code, message, UUID.randomUUID().toString())).build();
    }
}
