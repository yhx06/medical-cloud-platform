package com.medcloud.platform.tenant.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.tenant.infrastructure.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform/tenants")
public class PlatformTenantController {
    private final TenantRepository tenants;

    public PlatformTenantController(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ApiResponse<List<TenantResponse>> list(HttpServletRequest request) {
        List<TenantResponse> data = tenants.findAll().stream().map(TenantResponse::from).toList();
        return ApiResponse.success(data, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME).toString();
    }
}
