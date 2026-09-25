package com.medcloud.platform.schedule.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.schedule.application.ScheduleService;
import com.medcloud.platform.schedule.application.ScheduleService.ScheduleSlotResponse;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.tenant.domain.Department;
import com.medcloud.platform.tenant.domain.Tenant;
import com.medcloud.platform.tenant.domain.TenantStatus;
import com.medcloud.platform.tenant.infrastructure.DepartmentRepository;
import com.medcloud.platform.tenant.infrastructure.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final ScheduleService scheduleService;
    private final TenantRepository tenants;
    private final DepartmentRepository departments;

    public CatalogController(
            ScheduleService scheduleService,
            TenantRepository tenants,
            DepartmentRepository departments
    ) {
        this.scheduleService = scheduleService;
        this.tenants = tenants;
        this.departments = departments;
    }

    @GetMapping("/tenants")
    ApiResponse<List<CatalogTenantResponse>> tenants(HttpServletRequest request) {
        List<CatalogTenantResponse> data = tenants.findAllByStatusOrderByNameAsc(TenantStatus.ACTIVE).stream()
                .map(CatalogTenantResponse::from)
                .toList();
        return ApiResponse.success(data, requestId(request));
    }

    @GetMapping("/tenants/{tenantId}/departments")
    ApiResponse<List<CatalogDepartmentResponse>> departments(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        if (tenants.findByIdAndStatus(tenantId, TenantStatus.ACTIVE).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        List<CatalogDepartmentResponse> data = departments
                .findAllByTenantIdAndStatusOrderByNameAsc(tenantId, TenantStatus.ACTIVE).stream()
                .map(CatalogDepartmentResponse::from)
                .toList();
        return ApiResponse.success(data, requestId(request));
    }

    @GetMapping("/schedule-slots")
    ApiResponse<CatalogPage<ScheduleSlotResponse>> scheduleSlots(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Pageable pageable,
            HttpServletRequest request
    ) {
        Page<ScheduleSlotResponse> result = scheduleService.searchCatalog(
                tenantId, departmentId, date, pageable);
        return ApiResponse.success(CatalogPage.from(result), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }

    record CatalogPage<T>(
            List<T> content,
            long totalElements,
            int totalPages,
            int size,
            int number
    ) {
        static <T> CatalogPage<T> from(Page<T> page) {
            return new CatalogPage<>(
                    page.getContent(), page.getTotalElements(), page.getTotalPages(), page.getSize(), page.getNumber());
        }
    }

    record CatalogTenantResponse(UUID id, String name) {
        static CatalogTenantResponse from(Tenant tenant) {
            return new CatalogTenantResponse(tenant.getId(), tenant.getName());
        }
    }

    record CatalogDepartmentResponse(UUID id, UUID tenantId, String name, String description) {
        static CatalogDepartmentResponse from(Department department) {
            return new CatalogDepartmentResponse(
                    department.getId(), department.getTenantId(), department.getName(), department.getDescription());
        }
    }
}
