package com.medcloud.platform.appointment.api;

import com.medcloud.platform.appointment.application.AppointmentService;
import com.medcloud.platform.appointment.application.AppointmentService.AppointmentResponse;
import com.medcloud.platform.audit.application.AuditMutationBoundary;
import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/patient/appointments")
@PreAuthorize("hasRole('PATIENT')")
public class PatientAppointmentController {
    private final AppointmentService appointmentService;
    private final AuditMutationBoundary audit;

    public PatientAppointmentController(AppointmentService appointmentService, AuditMutationBoundary audit) {
        this.appointmentService = appointmentService;
        this.audit = audit;
    }

    @PostMapping
    ApiResponse<AppointmentResponse> book(
            @AuthenticationPrincipal CurrentUser patient,
            @Valid @RequestBody BookAppointmentRequest body,
            HttpServletRequest request
    ) {
        AppointmentResponse appointment = audit.execute(patient, AuditAction.APPOINTMENT_BOOKED, "APPOINTMENT", null,
                requestId(request), () -> appointmentService.book(patient, body.slotId()), AppointmentResponse::id);
        return ApiResponse.success(appointment, requestId(request));
    }

    @GetMapping
    ApiResponse<List<AppointmentResponse>> list(
            @AuthenticationPrincipal CurrentUser patient,
            HttpServletRequest request
    ) {
        return ApiResponse.success(appointmentService.patientAppointments(patient), requestId(request));
    }

    @GetMapping("/{id}")
    ApiResponse<AppointmentResponse> get(
            @AuthenticationPrincipal CurrentUser patient,
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        return ApiResponse.success(appointmentService.patientAppointment(patient, id), requestId(request));
    }

    @PostMapping("/{id}/cancel")
    ApiResponse<AppointmentResponse> cancel(
            @AuthenticationPrincipal CurrentUser patient,
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        AppointmentResponse appointment = audit.execute(patient, AuditAction.APPOINTMENT_CANCELLED, "APPOINTMENT", id,
                requestId(request), () -> appointmentService.cancel(patient, id), AppointmentResponse::id);
        return ApiResponse.success(appointment, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }

    public record BookAppointmentRequest(@NotNull UUID slotId) {
    }
}
