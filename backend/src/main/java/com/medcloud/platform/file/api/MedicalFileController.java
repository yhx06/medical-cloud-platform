package com.medcloud.platform.file.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.audit.application.AuditMutationBoundary;
import com.medcloud.platform.audit.application.AuditRequestIds;
import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditObjectType;
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
    private final AuditMutationBoundary audit;

    public MedicalFileController(MedicalFileService files, AuditMutationBoundary audit) {
        this.files = files;
        this.audit = audit;
    }

    @PostMapping("/doctor/records/{recordId}/files")
    @PreAuthorize("hasRole('DOCTOR')")
    ApiResponse<FileResponse> upload(
            @AuthenticationPrincipal CurrentUser doctor,
            @PathVariable UUID recordId,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request
    ) {
        FileResponse stored = audit.execute(doctor, AuditAction.FILE_UPLOADED, AuditObjectType.FILE, null,
                AuditRequestIds.from(request), () -> files.upload(doctor, recordId, file), FileResponse::id);
        return ApiResponse.success(stored, requestId(request));
    }

    @GetMapping("/records/{recordId}/files")
    ApiResponse<List<FileResponse>> listMetadata(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID recordId,
            HttpServletRequest request
    ) {
        List<FileResponse> metadata = audit.execute(user, AuditAction.FILE_METADATA_ACCESSED, AuditObjectType.CONSULTATION, recordId,
                AuditRequestIds.from(request), () -> files.listMetadata(user, recordId), ignored -> recordId);
        return ApiResponse.success(metadata, requestId(request));
    }

    @PostMapping("/files/{fileId}/download-url")
    ApiResponse<DownloadUrlResponse> createDownloadUrl(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID fileId,
            HttpServletRequest request
    ) {
        DownloadUrlResponse download = audit.execute(user, AuditAction.FILE_DOWNLOAD_URL_CREATED, AuditObjectType.FILE, fileId,
                AuditRequestIds.from(request), () -> files.createDownloadUrl(user, fileId), ignored -> fileId);
        return ApiResponse.success(download, requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }
}
