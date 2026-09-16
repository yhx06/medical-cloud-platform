package com.medcloud.platform.common.error;

import com.medcloud.platform.common.api.ApiError;
import com.medcloud.platform.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(ErrorCode.VALIDATION_ERROR, request, fieldErrors);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(BusinessException exception, HttpServletRequest request) {
        return error(exception.getCode(), request, Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request
    ) {
        return error(ErrorCode.FILE_TOO_LARGE, request, Map.of());
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    ResponseEntity<ApiError> handleAuthorizationDenied(
            AuthorizationDeniedException exception,
            HttpServletRequest request
    ) {
        return error(ErrorCode.AUTH_FORBIDDEN, request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        return error(ErrorCode.INTERNAL_ERROR, request, Map.of());
    }

    private ResponseEntity<ApiError> error(
            ErrorCode code,
            HttpServletRequest request,
            Map<String, String> fieldErrors
    ) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        String requestId = value == null ? "unknown" : value.toString();
        ApiError body = new ApiError(code.name(), code.message(), requestId, fieldErrors);
        return ResponseEntity.status(code.status()).body(body);
    }
}
