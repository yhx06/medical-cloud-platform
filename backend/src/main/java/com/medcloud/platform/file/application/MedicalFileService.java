package com.medcloud.platform.file.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.consultation.domain.Consultation;
import com.medcloud.platform.consultation.domain.ConsultationStatus;
import com.medcloud.platform.consultation.infrastructure.ConsultationRepository;
import com.medcloud.platform.file.domain.FileMetadata;
import com.medcloud.platform.file.infrastructure.FileMetadataRepository;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class MedicalFileService {
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private static final Map<String, String> MEDIA_TYPE_BY_SUFFIX = Map.of(
            ".pdf", "application/pdf",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".png", "image/png");

    private final ConsultationRepository consultations;
    private final FileMetadataRepository metadata;
    private final FileAccessPort files;

    public MedicalFileService(
            ConsultationRepository consultations,
            FileMetadataRepository metadata,
            FileAccessPort files
    ) {
        this.consultations = consultations;
        this.metadata = metadata;
        this.files = files;
    }

    @Transactional
    public FileResponse upload(CurrentUser doctor, UUID recordId, MultipartFile file) {
        requireRole(doctor, UserRole.DOCTOR);
        Consultation record = findRecord(recordId);
        requireAssignedDoctor(doctor, record);
        ValidatedFile validated = validate(file);

        String objectKey;
        try (InputStream content = file.getInputStream()) {
            objectKey = files.store(
                    doctor.userId(),
                    record.getTenantId(),
                    record.getId(),
                    validated.filename(),
                    content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        FileMetadata stored = FileMetadata.create(
                record.getTenantId(),
                record.getId(),
                doctor.userId(),
                validated.filename(),
                objectKey,
                validated.mediaType(),
                file.getSize());
        try {
            return response(metadata.saveAndFlush(stored));
        } catch (RuntimeException persistenceFailure) {
            try {
                files.delete(objectKey);
            } catch (RuntimeException deletionFailure) {
                persistenceFailure.addSuppressed(deletionFailure);
            }
            throw persistenceFailure;
        }
    }

    @Transactional(readOnly = true)
    public List<FileResponse> listMetadata(CurrentUser user, UUID recordId) {
        Consultation record = findRecord(recordId);
        requireMetadataAccess(user, record);
        return metadata.findAllByRecordIdOrderByCreatedAtAsc(record.getId())
                .stream()
                .map(MedicalFileService::response)
                .toList();
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse createDownloadUrl(CurrentUser user, UUID fileId) {
        if (user == null || (user.role() != UserRole.DOCTOR && user.role() != UserRole.PATIENT)) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        FileMetadata stored = metadata.findById(fileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Consultation record = findRecord(stored.getRecordId());
        if (!record.getTenantId().equals(stored.getTenantId())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        requireDownloadAccess(user, record);
        return new DownloadUrlResponse(
                files.presignGet(stored.getObjectKey(), DOWNLOAD_TTL),
                Instant.now().plus(DOWNLOAD_TTL));
    }

    private Consultation findRecord(UUID recordId) {
        if (recordId == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return consultations.findById(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static ValidatedFile validate(MultipartFile file) {
        if (file == null) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        String filename = safeFilename(file.getOriginalFilename());
        int suffixStart = filename.lastIndexOf('.');
        String suffix = suffixStart < 0 ? "" : filename.substring(suffixStart).toLowerCase(Locale.ROOT);
        String expectedMediaType = MEDIA_TYPE_BY_SUFFIX.get(suffix);
        String declaredMediaType = file.getContentType() == null
                ? ""
                : file.getContentType().trim().toLowerCase(Locale.ROOT);
        if (expectedMediaType == null || !expectedMediaType.equals(declaredMediaType)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        }
        return new ValidatedFile(filename, expectedMediaType);
    }

    private static String safeFilename(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        String normalized = originalFilename.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static void requireRole(CurrentUser user, UserRole role) {
        if (user == null || user.userId() == null || user.role() != role) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireAssignedDoctor(CurrentUser doctor, Consultation record) {
        if (doctor.tenantId() == null
                || !record.getDoctorId().equals(doctor.userId())
                || !record.getTenantId().equals(doctor.tenantId())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireMetadataAccess(CurrentUser user, Consultation record) {
        if (user == null || user.userId() == null || user.role() == null) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        switch (user.role()) {
            case DOCTOR -> requireAssignedDoctor(user, record);
            case PATIENT -> requirePatientOwner(user, record);
            case HOSPITAL_ADMIN -> {
                if (user.tenantId() == null || !record.getTenantId().equals(user.tenantId())) {
                    throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
                }
            }
            case PLATFORM_ADMIN -> throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static void requireDownloadAccess(CurrentUser user, Consultation record) {
        if (user.role() == UserRole.DOCTOR) {
            requireAssignedDoctor(user, record);
            return;
        }
        requirePatientOwner(user, record);
    }

    private static void requirePatientOwner(CurrentUser patient, Consultation record) {
        if (patient.role() != UserRole.PATIENT
                || !record.getPatientId().equals(patient.userId())
                || record.getStatus() != ConsultationStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
    }

    private static FileResponse response(FileMetadata stored) {
        return new FileResponse(
                stored.getId(),
                stored.getRecordId(),
                stored.getOriginalFilename(),
                stored.getMediaType(),
                stored.getSizeBytes(),
                stored.getCreatedAt());
    }

    private record ValidatedFile(String filename, String mediaType) {
    }

    public record FileResponse(
            UUID id,
            UUID recordId,
            String originalFilename,
            String mediaType,
            long sizeBytes,
            Instant createdAt
    ) {
    }

    public record DownloadUrlResponse(String url, Instant expiresAt) {
    }
}
