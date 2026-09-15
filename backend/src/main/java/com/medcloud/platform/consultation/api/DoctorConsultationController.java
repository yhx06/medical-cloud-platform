package com.medcloud.platform.consultation.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.consultation.application.ConsultationService;
import com.medcloud.platform.consultation.application.ConsultationService.CorrectionCommand;
import com.medcloud.platform.consultation.application.ConsultationService.DraftCommand;
import com.medcloud.platform.consultation.application.ConsultationService.MedicalRecordResponse;
import com.medcloud.platform.consultation.application.ConsultationService.PrescriptionItemCommand;
import com.medcloud.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/doctor")
@PreAuthorize("hasRole('DOCTOR')")
public class DoctorConsultationController {
    private final ConsultationService consultationService;

    public DoctorConsultationController(ConsultationService consultationService) {
        this.consultationService = consultationService;
    }

    @PostMapping("/appointments/{appointmentId}/start")
    ApiResponse<MedicalRecordResponse> start(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID appointmentId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(consultationService.start(doctor, appointmentId), requestId(request));
    }

    @PutMapping("/consultations/{appointmentId}/draft")
    ApiResponse<MedicalRecordResponse> saveDraft(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody DraftRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                consultationService.saveDraft(doctor, appointmentId, body.toCommand()),
                requestId(request));
    }

    @PostMapping("/consultations/{appointmentId}/complete")
    ApiResponse<MedicalRecordResponse> complete(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID appointmentId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(consultationService.complete(doctor, appointmentId), requestId(request));
    }

    @PostMapping("/records/{recordId}/corrections")
    ApiResponse<MedicalRecordResponse> appendCorrection(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID recordId,
            @Valid @RequestBody CorrectionRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                consultationService.appendCorrection(doctor, recordId, new CorrectionCommand(body.reason())),
                requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }

    public record DraftRequest(
            String complaint,
            String diagnosis,
            String advice,
            List<@Valid PrescriptionItemRequest> prescriptionItems
    ) {
        DraftCommand toCommand() {
            List<PrescriptionItemCommand> items = prescriptionItems == null
                    ? List.of()
                    : prescriptionItems.stream().map(PrescriptionItemRequest::toCommand).toList();
            return new DraftCommand(complaint, diagnosis, advice, items);
        }
    }

    public record PrescriptionItemRequest(
            @NotBlank String medicationName,
            @NotBlank String dosage,
            @NotBlank String frequency,
            @Positive int durationDays
    ) {
        PrescriptionItemCommand toCommand() {
            return new PrescriptionItemCommand(medicationName, dosage, frequency, durationDays);
        }
    }

    public record CorrectionRequest(@NotBlank String reason) {
    }
}
