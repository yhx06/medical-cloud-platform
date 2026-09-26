package com.medcloud.course.paas;

import com.medcloud.course.contract.ApiError;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.auth.AccessDeniedException;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApiFailureTest {
    @Test
    void authenticationFailureIs401WithStablePublicError() {
        Response response = new ApiFailureMapper().toResponse(new SecurityException("secret token is bad"));
        assertEquals(401, response.getStatus());
        ApiError error = (ApiError) response.getEntity();
        assertEquals("UNAUTHORIZED", error.getCode());
        assertFalse(error.getMessage().contains("secret token"));
    }

    @Test
    void bookingConflictIs409() {
        Response response = new ApiFailureMapper().toResponse(new IllegalStateException("slot full"));
        assertEquals(409, response.getStatus());
        assertEquals("CONFLICT", ((ApiError) response.getEntity()).getCode());
    }

    @Test
    void crossTenantAccessIs403() {
        Response response = new ApiFailureMapper().toResponse(new AccessDeniedException());
        assertEquals(403, response.getStatus());
        assertEquals("FORBIDDEN", ((ApiError) response.getEntity()).getCode());
    }
}
