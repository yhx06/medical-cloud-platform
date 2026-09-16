package com.medcloud.platform.tenant.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.audit.application.AuditMutationBoundary;
import com.medcloud.platform.audit.application.AuditRequestIds;
import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditObjectType;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.tenant.application.HospitalDirectoryService;
import com.medcloud.platform.tenant.application.HospitalDirectoryService.CreateDepartmentRequest;
import com.medcloud.platform.tenant.application.HospitalDirectoryService.CreateDoctorRequest;
import com.medcloud.platform.tenant.application.HospitalDirectoryService.DepartmentResponse;
import com.medcloud.platform.tenant.application.HospitalDirectoryService.DoctorResponse;
import com.medcloud.platform.tenant.application.HospitalDirectoryService.UpdateDepartmentRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/hospital")
@PreAuthorize("hasRole('HOSPITAL_ADMIN')")
public class HospitalDirectoryController {
    private final HospitalDirectoryService directoryService;
    private final AuditMutationBoundary audit;

    public HospitalDirectoryController(HospitalDirectoryService directoryService, AuditMutationBoundary audit) {
        this.directoryService = directoryService;
        this.audit = audit;
    }

    @GetMapping("/departments")
    ApiResponse<List<DepartmentResponse>> listDepartments(
            @AuthenticationPrincipal CurrentUser actor,
            HttpServletRequest request
    ) {
        return ApiResponse.success(directoryService.listDepartments(actor), requestId(request));
    }

    @PostMapping("/departments")
    ApiResponse<DepartmentResponse> createDepartment(
            @AuthenticationPrincipal CurrentUser actor,
            @Valid @RequestBody CreateDepartmentRequest body,
            HttpServletRequest request
    ) {
        DepartmentResponse department = audit.execute(actor, AuditAction.DEPARTMENT_CREATED, AuditObjectType.DEPARTMENT, null,
                AuditRequestIds.from(request), () -> directoryService.createDepartment(actor, body), DepartmentResponse::id);
        return ApiResponse.success(department, requestId(request));
    }

    @PutMapping("/departments/{id}")
    ApiResponse<DepartmentResponse> updateDepartment(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDepartmentRequest body,
            HttpServletRequest request
    ) {
        DepartmentResponse department = audit.execute(actor, AuditAction.DEPARTMENT_UPDATED, AuditObjectType.DEPARTMENT, id,
                AuditRequestIds.from(request), () -> directoryService.updateDepartment(actor, id, body), DepartmentResponse::id);
        return ApiResponse.success(department, requestId(request));
    }

    @GetMapping("/doctors")
    ApiResponse<List<DoctorResponse>> listDoctors(
            @AuthenticationPrincipal CurrentUser actor,
            HttpServletRequest request
    ) {
        return ApiResponse.success(directoryService.listDoctors(actor), requestId(request));
    }

    @PostMapping("/doctors")
    ApiResponse<DoctorResponse> createDoctor(
            @AuthenticationPrincipal CurrentUser actor,
            @Valid @RequestBody CreateDoctorRequest body,
            HttpServletRequest request
    ) {
        DoctorResponse doctor = audit.execute(actor, AuditAction.DOCTOR_CREATED, AuditObjectType.DOCTOR, null,
                AuditRequestIds.from(request), () -> directoryService.createDoctor(actor, body), DoctorResponse::id);
        return ApiResponse.success(doctor, requestId(request));
    }

    @PutMapping("/doctors/{id}/status")
    ApiResponse<DoctorResponse> changeDoctorStatus(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody DoctorStatusRequest body,
            HttpServletRequest request
    ) {
        DoctorResponse doctor = audit.execute(actor, AuditAction.DOCTOR_STATUS_CHANGED, AuditObjectType.DOCTOR, id,
                AuditRequestIds.from(request), () -> directoryService.changeDoctorStatus(actor, id, body.status()), DoctorResponse::id);
        return ApiResponse.success(doctor, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }

    record DoctorStatusRequest(@NotBlank String status) {
    }
}
