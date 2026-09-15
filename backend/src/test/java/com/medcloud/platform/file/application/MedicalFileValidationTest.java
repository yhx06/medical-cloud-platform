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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MedicalFileValidationTest {
    private static final long TEN_MIB = 10L * 1024 * 1024;
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECORD_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID DOCTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID PATIENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private ConsultationRepository consultations;
    private FileMetadataRepository metadata;
    private RecordingFileAccessPort files;
    private MedicalFileService service;

    @BeforeEach
    void setUp() {
        consultations = mock(ConsultationRepository.class);
        metadata = mock(FileMetadataRepository.class);
        files = new RecordingFileAccessPort();
        service = new MedicalFileService(consultations, metadata, files);

        Consultation record = mock(Consultation.class);
        when(record.getId()).thenReturn(RECORD_ID);
        when(record.getTenantId()).thenReturn(TENANT_ID);
        when(record.getDoctorId()).thenReturn(DOCTOR_ID);
        when(record.getPatientId()).thenReturn(PATIENT_ID);
        when(record.getStatus()).thenReturn(ConsultationStatus.COMPLETED);
        when(consultations.findById(RECORD_ID)).thenReturn(Optional.of(record));
        when(metadata.saveAndFlush(any(FileMetadata.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void rejectsAnExecutableBeforeWritingAnyObject() {
        MockMultipartFile executable = file("payload.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> service.upload(doctor(), RECORD_ID, executable))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.FILE_TYPE_UNSUPPORTED));

        assertThat(files.storedObjectKeys).isEmpty();
    }

    @Test
    void rejectsAWhitelistedMediaTypeWhenItsSuffixDoesNotMatch() {
        MockMultipartFile mismatched = file("scan.pdf", "image/png", new byte[]{1});

        assertThatThrownBy(() -> service.upload(doctor(), RECORD_ID, mismatched))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.FILE_TYPE_UNSUPPORTED));

        assertThat(files.storedObjectKeys).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("allowedFiles")
    void acceptsEveryWhitelistedSuffixAndMatchingMediaType(String filename, String mediaType) {
        MedicalFileService.FileResponse response = service.upload(
                doctor(), RECORD_ID, file(filename, mediaType, new byte[]{1, 2, 3}));

        assertThat(response.originalFilename()).isEqualTo(filename);
        assertThat(response.mediaType()).isEqualTo(mediaType);
        assertThat(response.sizeBytes()).isEqualTo(3);
        assertThat(files.lastStoredSize).isEqualTo(3);
    }

    @Test
    void acceptsAFileExactlyTenMib() {
        MedicalFileService.FileResponse response = service.upload(
                doctor(), RECORD_ID, file("boundary.pdf", "application/pdf", new byte[(int) TEN_MIB]));

        assertThat(response.sizeBytes()).isEqualTo(TEN_MIB);
        assertThat(files.lastStoredSize).isEqualTo(TEN_MIB);
    }

    @Test
    void rejectsTheFirstByteOverTenMibBeforeWritingAnyObject() {
        MockMultipartFile overLimit = file(
                "too-large.pdf", "application/pdf", new byte[(int) TEN_MIB + 1]);

        assertThatThrownBy(() -> service.upload(doctor(), RECORD_ID, overLimit))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE));

        assertThat(files.storedObjectKeys).isEmpty();
    }

    @Test
    void stripsClientPathsAndPersistsMetadataOnlyAfterTheObjectExists() {
        when(metadata.saveAndFlush(any(FileMetadata.class))).thenAnswer(invocation -> {
            assertThat(files.storedObjectKeys).hasSize(1);
            FileMetadata saved = invocation.getArgument(0);
            assertThat(saved.getObjectKey()).isEqualTo(files.storedObjectKeys.getFirst());
            return saved;
        });

        MedicalFileService.FileResponse response = service.upload(
                doctor(), RECORD_ID, file("..\\fakepath/scan.PDF", "APPLICATION/PDF", new byte[]{1}));

        assertThat(files.lastFilename).isEqualTo("scan.PDF");
        assertThat(response.originalFilename()).isEqualTo("scan.PDF");
        assertThat(response.mediaType()).isEqualTo("application/pdf");
        assertThat(files.storedObjectKeys.getFirst())
                .startsWith(TENANT_ID + "/" + RECORD_ID + "/")
                .doesNotContain("scan")
                .doesNotContain("..");
    }

    @Test
    void deletesTheUploadedObjectWhenMetadataFlushFails() {
        IllegalStateException persistenceFailure = new IllegalStateException("metadata flush failed");
        when(metadata.saveAndFlush(any(FileMetadata.class))).thenThrow(persistenceFailure);

        assertThatThrownBy(() -> service.upload(
                doctor(), RECORD_ID, file("scan.pdf", "application/pdf", new byte[]{1})))
                .isSameAs(persistenceFailure);

        assertThat(files.deletedObjectKeys).containsExactly(files.storedObjectKeys.getFirst());
    }

    @Test
    void preservesTheMetadataFailureWhenCompensatingDeleteAlsoFails() {
        IllegalStateException persistenceFailure = new IllegalStateException("metadata flush failed");
        IllegalStateException deletionFailure = new IllegalStateException("object delete failed");
        when(metadata.saveAndFlush(any(FileMetadata.class))).thenThrow(persistenceFailure);
        files.deletionFailure = deletionFailure;

        assertThatThrownBy(() -> service.upload(
                doctor(), RECORD_ID, file("scan.pdf", "application/pdf", new byte[]{1})))
                .isSameAs(persistenceFailure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(deletionFailure));
    }

    @Test
    void createsAnAuthorizedDownloadUrlWithAnExactFiveMinuteTtl() {
        FileMetadata stored = FileMetadata.create(
                TENANT_ID,
                RECORD_ID,
                DOCTOR_ID,
                "scan.pdf",
                TENANT_ID + "/" + RECORD_ID + "/opaque.pdf",
                "application/pdf",
                100);
        when(metadata.findById(stored.getId())).thenReturn(Optional.of(stored));

        MedicalFileService.DownloadUrlResponse response = service.createDownloadUrl(patient(), stored.getId());

        assertThat(response.url()).isEqualTo("https://objects.example/signed");
        assertThat(files.presignedObjectKey).isEqualTo(stored.getObjectKey());
        assertThat(files.presignedTtl).isEqualTo(Duration.ofMinutes(5));
    }

    private static Stream<Arguments> allowedFiles() {
        return Stream.of(
                Arguments.of("report.pdf", "application/pdf"),
                Arguments.of("photo.jpg", "image/jpeg"),
                Arguments.of("photo.jpeg", "image/jpeg"),
                Arguments.of("image.png", "image/png"));
    }

    private static MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    private static CurrentUser doctor() {
        return new CurrentUser(DOCTOR_ID, "doctor", UserRole.DOCTOR, TENANT_ID);
    }

    private static CurrentUser patient() {
        return new CurrentUser(PATIENT_ID, "patient", UserRole.PATIENT, null);
    }

    private static final class RecordingFileAccessPort implements FileAccessPort {
        private final List<String> storedObjectKeys = new ArrayList<>();
        private final List<String> deletedObjectKeys = new ArrayList<>();
        private String lastFilename;
        private long lastStoredSize;
        private RuntimeException deletionFailure;
        private String presignedObjectKey;
        private Duration presignedTtl;

        @Override
        public String store(
                UUID actorId,
                UUID tenantId,
                UUID businessObjectId,
                String fileName,
                InputStream content
        ) {
            lastFilename = fileName;
            try {
                lastStoredSize = content.readAllBytes().length;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            String suffix = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
            String objectKey = tenantId + "/" + businessObjectId + "/" + UUID.randomUUID() + suffix;
            storedObjectKeys.add(objectKey);
            return objectKey;
        }

        @Override
        public InputStream load(UUID actorId, UUID tenantId, UUID businessObjectId, String objectKey) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void delete(String objectKey) {
            deletedObjectKeys.add(objectKey);
            if (deletionFailure != null) {
                throw deletionFailure;
            }
        }

        @Override
        public String presignGet(String objectKey, Duration ttl) {
            presignedObjectKey = objectKey;
            presignedTtl = ttl;
            return "https://objects.example/signed";
        }
    }
}
