package com.medcloud.platform.schedule.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.schedule.application.ScheduleService;
import com.medcloud.platform.schedule.application.ScheduleService.PublishSlotRequest;
import com.medcloud.platform.schedule.application.ScheduleService.ScheduleSlotResponse;
import com.medcloud.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hospital/schedule-slots")
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public class HospitalScheduleController {
    private final ScheduleService scheduleService;

    public HospitalScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PostMapping
    ApiResponse<ScheduleSlotResponse> publish(
            @AuthenticationPrincipal CurrentUser actor,
            @Valid @RequestBody PublishSlotRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.success(scheduleService.publish(actor, body), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }
}
