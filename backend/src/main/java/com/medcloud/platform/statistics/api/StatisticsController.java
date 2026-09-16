package com.medcloud.platform.statistics.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.statistics.application.StatisticsService;
import com.medcloud.platform.statistics.application.StatisticsService.HospitalStatisticsResponse;
import com.medcloud.platform.statistics.application.StatisticsService.PlatformStatisticsResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
public class StatisticsController {
    private final StatisticsService statistics;

    public StatisticsController(StatisticsService statistics) {
        this.statistics = statistics;
    }

    @GetMapping("/hospital/statistics")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    ApiResponse<HospitalStatisticsResponse> hospital(
            @AuthenticationPrincipal CurrentUser admin,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        return ApiResponse.success(statistics.hospital(admin, from, to), requestId(request));
    }

    @GetMapping("/platform/statistics")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ApiResponse<PlatformStatisticsResponse> platform(
            @AuthenticationPrincipal CurrentUser admin,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        return ApiResponse.success(statistics.platform(admin, from, to), requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }
}
