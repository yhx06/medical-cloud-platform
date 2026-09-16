package com.medcloud.platform.identity.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.audit.application.AuditMutationBoundary;
import com.medcloud.platform.audit.application.AuditRequestIds;
import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditObjectType;
import com.medcloud.platform.audit.domain.AuditResult;
import com.medcloud.platform.identity.application.AuthenticationService;
import com.medcloud.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthenticationService authenticationService;
    private final AuditMutationBoundary audit;

    public AuthController(AuthenticationService authenticationService, AuditMutationBoundary audit) {
        this.authenticationService = authenticationService;
        this.audit = audit;
    }

    @PostMapping("/auth/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String requestId = requestId(servletRequest);
        java.util.UUID auditRequestId = AuditRequestIds.from(servletRequest);
        try {
            LoginResponse response = authenticationService.login(request.username(), request.password());
            CurrentUserResponse user = response.user();
            audit.recordOutcome(
                    new CurrentUser(user.userId(), user.username(), user.role(), user.tenantId()),
                    AuditAction.LOGIN_SUCCEEDED,
                    AuditObjectType.USER_ACCOUNT,
                    user.userId(),
                    AuditResult.SUCCESS,
                    auditRequestId);
            return ApiResponse.success(response, requestId);
        } catch (BusinessException exception) {
            audit.recordOutcome(null, AuditAction.LOGIN_REJECTED, AuditObjectType.USER_ACCOUNT, null,
                    AuditResult.REJECTED, auditRequestId);
            throw exception;
        }
    }

    @GetMapping("/me")
    ApiResponse<CurrentUserResponse> me(
            @AuthenticationPrincipal CurrentUser currentUser,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(CurrentUserResponse.from(currentUser), requestId(servletRequest));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }
}
