package com.medcloud.course.paas;

import com.medcloud.course.contract.ApiError;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.auth.AccessDeniedException;
import org.junit.jupiter.api.Test;

import javax.ejb.EJBException;
import javax.ws.rs.NotSupportedException;
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

    @Test
    void ejbWrappedAuthenticationFailureKeeps401Contract() {
        Response response = new ApiFailureMapper().toResponse(
                new EJBException(new SecurityException("invalid credentials")));
        assertEquals(401, response.getStatus());
        assertEquals("UNAUTHORIZED", ((ApiError) response.getEntity()).getCode());
    }

    @Test
    void ejbWrappedAccessDeniedKeeps403Contract() {
        Response response = new ApiFailureMapper().toResponse(
                new EJBException(new AccessDeniedException()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void ejbWrappedConflictKeeps409Contract() {
        Response response = new ApiFailureMapper().toResponse(
                new EJBException(new IllegalStateException("already booked")));
        assertEquals(409, response.getStatus());
    }

    @Test
    void unsupportedMediaTypeIsNotReportedAsServerFailure() {
        Response response = new ApiFailureMapper().toResponse(new NotSupportedException());
        assertEquals(415, response.getStatus());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", ((ApiError) response.getEntity()).getCode());
    }

    @Test
    void attachmentErrorsKeepStableCodesAndSafeMessages() {
        int[][] cases = {{400,400},{413,413},{415,415},{409,409},{429,429},{503,503}};
        String[] codes = {"FILE_INVALID","FILE_TOO_LARGE","FILE_TYPE_UNSUPPORTED","UPLOAD_IN_PROGRESS","FILE_LIMIT_REACHED","OBJECT_STORE_UNAVAILABLE"};
        for (int i = 0; i < codes.length; i++) {
            Response response = new ApiFailureMapper().toResponse(new com.medcloud.course.paas.attachment.AttachmentException(cases[i][0], codes[i]));
            assertEquals(cases[i][0], response.getStatus());
            ApiError error = (ApiError) response.getEntity();
            assertEquals(codes[i], error.getCode());
            assertFalse(error.getMessage().contains("Attachment operation failed"));
        }
    }

    @Test
    void attachmentReasonsFollowTheClosedVocabulary() {
        assertEquals(com.medcloud.course.contract.AuditReasonCode.BAD_REQUEST,
            ApiFailureMapper.reasonFor(new com.medcloud.course.paas.attachment.AttachmentException(413, "FILE_TOO_LARGE")));
        assertEquals(com.medcloud.course.contract.AuditReasonCode.CONFLICT,
            ApiFailureMapper.reasonFor(new com.medcloud.course.paas.attachment.AttachmentException(409, "FILE_STATE_CONFLICT")));
        assertEquals(com.medcloud.course.contract.AuditReasonCode.INTERNAL_ERROR,
            ApiFailureMapper.reasonFor(new com.medcloud.course.paas.attachment.AttachmentException(503, "OBJECT_STORE_UNAVAILABLE")));
        assertEquals(com.medcloud.course.contract.AuditReasonCode.UNAUTHORIZED,
            ApiFailureMapper.reasonFor(new com.medcloud.course.paas.attachment.AttachmentException(401, "UNAUTHORIZED")));
    }
}
