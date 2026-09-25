package com.medcloud.platform.tenant.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.audit.application.AuditMutationBoundary;
import com.medcloud.platform.audit.application.AuditRequestIds;
import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditObjectType;
import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserStatus;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.tenant.application.PlatformTenantService;
import com.medcloud.platform.tenant.application.PlatformTenantService.CreateAccountCommand;
import com.medcloud.platform.tenant.application.PlatformTenantService.CreateTenantCommand;
import com.medcloud.platform.tenant.domain.Tenant;
import com.medcloud.platform.tenant.domain.TenantStatus;
import com.medcloud.platform.tenant.infrastructure.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform")
public class PlatformTenantController {
    private final TenantRepository tenants;
    private final PlatformTenantService tenantService;
    private final AuditMutationBoundary audit;

    public PlatformTenantController(
            TenantRepository tenants,
            PlatformTenantService tenantService,
            AuditMutationBoundary audit
    ) {
        this.tenants = tenants;
        this.tenantService = tenantService;
        this.audit = audit;
    }

    @GetMapping("/tenants")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ApiResponse<List<TenantResponse>> list(HttpServletRequest request) {
        List<TenantResponse> data = tenants.findAll().stream().map(TenantResponse::from).toList();
        return ApiResponse.success(data, requestId(request));
    }

    @PostMapping("/tenants")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ApiResponse<TenantResponse> createTenant(
            @AuthenticationPrincipal CurrentUser actor,
            @Valid @RequestBody CreateTenantCommand command,
            HttpServletRequest request
    ) {
        Tenant tenant = audit.execute(actor, AuditAction.TENANT_CREATED, AuditObjectType.TENANT, null, AuditRequestIds.from(request),
                () -> tenantService.createTenant(actor, command), Tenant::getId);
        return ApiResponse.success(TenantResponse.from(tenant), requestId(request));
    }

    @PostMapping("/tenants/{tenantId}/admins")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ApiResponse<AccountResponse> createHospitalAdmin(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable UUID tenantId,
            @Valid @RequestBody CreateAccountCommand command,
            HttpServletRequest request
    ) {
        UserAccount account = audit.execute(actor, AuditAction.HOSPITAL_ADMIN_CREATED, AuditObjectType.USER_ACCOUNT, null,
                AuditRequestIds.from(request), () -> tenantService.createHospitalAdmin(actor, tenantId, command),
                UserAccount::getId);
        return ApiResponse.success(AccountResponse.from(account), requestId(request));
    }

    @GetMapping("/tenants/{tenantId}/administrators")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ApiResponse<List<AccountResponse>> hospitalAdministrators(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        List<AccountResponse> data = tenantService.hospitalAdministrators(actor, tenantId).stream()
                .map(AccountResponse::from)
                .toList();
        return ApiResponse.success(data, requestId(request));
    }

    @PatchMapping("/tenants/{tenantId}/status")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ApiResponse<TenantResponse> changeTenantStatus(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable UUID tenantId,
            @Valid @RequestBody StatusCommand command,
            HttpServletRequest request
    ) {
        Tenant tenant = audit.execute(actor, AuditAction.TENANT_STATUS_CHANGED, AuditObjectType.TENANT, tenantId,
                AuditRequestIds.from(request), () -> tenantService.changeTenantStatus(actor, tenantId, tenantStatus(command.status())),
                Tenant::getId);
        return ApiResponse.success(TenantResponse.from(tenant), requestId(request));
    }

    @PatchMapping("/accounts/{accountId}/status")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ApiResponse<AccountResponse> changeAccountStatus(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable UUID accountId,
            @Valid @RequestBody StatusCommand command,
            HttpServletRequest request
    ) {
        UserAccount account = audit.execute(actor, AuditAction.ACCOUNT_STATUS_CHANGED, AuditObjectType.USER_ACCOUNT, accountId,
                AuditRequestIds.from(request), () -> tenantService.changeAccountStatus(actor, accountId, userStatus(command.status())),
                UserAccount::getId);
        return ApiResponse.success(AccountResponse.from(account), requestId(request));
    }

    private TenantStatus tenantStatus(String value) {
        try {
            return TenantStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private UserStatus userStatus(String value) {
        try {
            return UserStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }

    record StatusCommand(@NotBlank String status) {
    }

    record AccountResponse(UUID id, String username, String role, String status, UUID tenantId) {
        static AccountResponse from(UserAccount account) {
            return new AccountResponse(
                    account.getId(), account.getUsername(), account.getRole().name(),
                    account.getStatus().name(), account.getTenantId());
        }
    }
}
