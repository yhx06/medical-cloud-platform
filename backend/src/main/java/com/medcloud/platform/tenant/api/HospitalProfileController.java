package com.medcloud.platform.tenant.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.tenant.application.TenantAccessPolicy;
import com.medcloud.platform.tenant.domain.TenantStatus;
import com.medcloud.platform.tenant.infrastructure.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hospital/profile")
public class HospitalProfileController {
    private final TenantRepository tenants;
    private final TenantAccessPolicy accessPolicy;

    public HospitalProfileController(TenantRepository tenants, TenantAccessPolicy accessPolicy) {
        this.tenants = tenants;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'HOSPITAL_ADMIN')")
    ApiResponse<TenantResponse> profile(
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request
    ) {
        accessPolicy.requireTenant(user, user.tenantId());
        TenantResponse data = tenants.findByIdAndStatus(user.tenantId(), TenantStatus.ACTIVE)
                .map(TenantResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return ApiResponse.success(data, request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME).toString());
    }
}
