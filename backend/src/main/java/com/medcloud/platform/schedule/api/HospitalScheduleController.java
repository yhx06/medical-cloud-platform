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
import com.medcloud.platform.schedule.domain.ScheduleSlotStatus;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

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

    @GetMapping
    ApiResponse<List<ScheduleSlotResponse>> list(
            @AuthenticationPrincipal CurrentUser actor,
            HttpServletRequest request
    ) {
        return ApiResponse.success(scheduleService.hospitalSlots(actor), requestId(request));
    }

    @PutMapping("/{slotId}")
    ApiResponse<ScheduleSlotResponse> changeStatus(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable UUID slotId,
            @Valid @RequestBody StatusRequest body,
            HttpServletRequest request
    ) {
        ScheduleSlotStatus status;
        try {
            status = ScheduleSlotStatus.valueOf(body.status());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        ScheduleSlotResponse slot = scheduleService.changeStatus(actor, slotId, status);
        return ApiResponse.success(slot, requestId(request));
    }

    record StatusRequest(@NotBlank String status) {
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }
}
