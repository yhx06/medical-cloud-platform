package com.medcloud.platform.consultation.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.audit.application.AuditMutationBoundary;
import com.medcloud.platform.audit.domain.AuditAction;
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
    private final AuditMutationBoundary audit;

    public DoctorConsultationController(ConsultationService consultationService, AuditMutationBoundary audit) {
        this.consultationService = consultationService;
        this.audit = audit;
    }

    @PostMapping("/appointments/{appointmentId}/start")
    ApiResponse<MedicalRecordResponse> start(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID appointmentId,
            HttpServletRequest request
    ) {
        MedicalRecordResponse consultation = audit.execute(doctor, AuditAction.CONSULTATION_STARTED, "CONSULTATION", null,
                requestId(request), () -> consultationService.start(doctor, appointmentId), MedicalRecordResponse::id);
        return ApiResponse.success(consultation, requestId(request));
    }

    @PutMapping("/consultations/{appointmentId}/draft")
    ApiResponse<MedicalRecordResponse> saveDraft(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody DraftRequest body,
            HttpServletRequest request
    ) {
        MedicalRecordResponse consultation = audit.execute(doctor, AuditAction.CONSULTATION_DRAFT_SAVED,
                "CONSULTATION", null, requestId(request),
                () -> consultationService.saveDraft(doctor, appointmentId, body.toCommand()), MedicalRecordResponse::id);
        return ApiResponse.success(consultation, requestId(request));
    }

    @PostMapping("/consultations/{appointmentId}/complete")
    ApiResponse<MedicalRecordResponse> complete(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID appointmentId,
            HttpServletRequest request
    ) {
        MedicalRecordResponse consultation = audit.execute(doctor, AuditAction.CONSULTATION_COMPLETED,
                "CONSULTATION", null, requestId(request),
                () -> consultationService.complete(doctor, appointmentId), MedicalRecordResponse::id);
        return ApiResponse.success(consultation, requestId(request));
    }

    @PostMapping("/records/{recordId}/corrections")
    ApiResponse<MedicalRecordResponse> appendCorrection(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID recordId,
            @Valid @RequestBody CorrectionRequest body,
            HttpServletRequest request
    ) {
        MedicalRecordResponse consultation = audit.execute(doctor, AuditAction.RECORD_CORRECTION_APPENDED,
                "CONSULTATION", recordId, requestId(request),
                () -> consultationService.appendCorrection(doctor, recordId, new CorrectionCommand(body.reason())),
                MedicalRecordResponse::id);
        return ApiResponse.success(consultation, requestId(request));
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
