package com.medcloud.platform.schedule.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.audit.application.AuditMutationBoundary;
import com.medcloud.platform.audit.application.AuditRequestIds;
import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditObjectType;
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
    private final AuditMutationBoundary audit;

    public HospitalScheduleController(ScheduleService scheduleService, AuditMutationBoundary audit) {
        this.scheduleService = scheduleService;
        this.audit = audit;
    }

    @PostMapping
    ApiResponse<ScheduleSlotResponse> publish(
            @AuthenticationPrincipal CurrentUser actor,
            @Valid @RequestBody PublishSlotRequest body,
            HttpServletRequest request
    ) {
        ScheduleSlotResponse slot = audit.execute(actor, AuditAction.SCHEDULE_PUBLISHED, AuditObjectType.SCHEDULE_SLOT, null,
                AuditRequestIds.from(request), () -> scheduleService.publish(actor, body), ScheduleSlotResponse::id);
        return ApiResponse.success(slot, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }
}
