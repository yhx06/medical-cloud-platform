package com.medcloud.platform.file.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.file.application.MedicalFileService;
import com.medcloud.platform.file.application.MedicalFileService.DownloadUrlResponse;
import com.medcloud.platform.file.application.MedicalFileService.FileResponse;
import com.medcloud.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class MedicalFileController {
    private final MedicalFileService files;

    public MedicalFileController(MedicalFileService files) {
        this.files = files;
    }

    @PostMapping("/doctor/records/{recordId}/files")
    @PreAuthorize("hasRole('DOCTOR')")
    ApiResponse<FileResponse> upload(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID recordId,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request
    ) {
        return ApiResponse.success(files.upload(doctor, recordId, file), requestId(request));
    }

    @GetMapping("/records/{recordId}/files")
    @PreAuthorize("hasAnyRole('DOCTOR', 'PATIENT', 'HOSPITAL_ADMIN')")
    ApiResponse<List<FileResponse>> listMetadata(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID recordId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(files.listMetadata(user, recordId), requestId(request));
    }

    @PostMapping("/files/{fileId}/download-url")
    @PreAuthorize("hasAnyRole('DOCTOR', 'PATIENT')")
    ApiResponse<DownloadUrlResponse> createDownloadUrl(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID fileId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(files.createDownloadUrl(user, fileId), requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }
}
