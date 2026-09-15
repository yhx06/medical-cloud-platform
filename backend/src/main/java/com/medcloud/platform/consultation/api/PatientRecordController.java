package com.medcloud.platform.consultation.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.consultation.application.ConsultationService;
import com.medcloud.platform.consultation.application.ConsultationService.MedicalRecordResponse;
import com.medcloud.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/patient/records")
@PreAuthorize("hasRole('PATIENT')")
public class PatientRecordController {
    private final ConsultationService consultationService;

    public PatientRecordController(ConsultationService consultationService) {
        this.consultationService = consultationService;
    }

    @GetMapping
    ApiResponse<List<MedicalRecordResponse>> list(
            @AuthenticationPrincipal CurrentUser patient,
            HttpServletRequest request
    ) {
        return ApiResponse.success(consultationService.patientRecords(patient), requestId(request));
    }

    @GetMapping("/{recordId}")
    ApiResponse<MedicalRecordResponse> get(
            @AuthenticationPrincipal CurrentUser patient,
            @PathVariable UUID recordId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                consultationService.patientRecord(patient, recordId),
                requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }
}
