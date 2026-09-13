package com.medcloud.platform.schedule.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.schedule.application.ScheduleService;
import com.medcloud.platform.schedule.application.ScheduleService.ScheduleSlotResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
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

    public CatalogController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
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
}
