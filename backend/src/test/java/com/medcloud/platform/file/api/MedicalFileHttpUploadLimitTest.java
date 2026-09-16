package com.medcloud.platform.file.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.file.application.FileAccessPort;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MedicalFileHttpUploadLimitTest {
    private static final int TEN_MIB = 10 * 1024 * 1024;
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PATIENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DOCTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private final TestRestTemplate http;
    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RecordingFileAccessPort files;

    private UUID recordId;
    private UUID appointmentId;
    private UUID slotId;
    private UUID departmentId;

    @Autowired
    MedicalFileHttpUploadLimitTest(
            TestRestTemplate http,
            JwtService jwtService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RecordingFileAccessPort files
    ) {
        this.http = http;
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.files = files;
    }

    @BeforeEach
    void createCompletedRecord() {
        recordId = UUID.randomUUID();
        appointmentId = UUID.randomUUID();
        slotId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        files.reset();

        jdbcTemplate.update("""
                insert into department
                    (id, tenant_id, name, description, status, created_at, updated_at)
                values (?, ?, ?, '', 'ACTIVE', current_timestamp, current_timestamp)
                """, departmentId.toString(), TENANT_ID.toString(), "HTTP附件科-" + departmentId);
        jdbcTemplate.update("""
                insert into schedule_slot
                    (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                     capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '09:00', '10:00', 1, ?, 'PUBLISHED', 0,
                        current_timestamp, current_timestamp)
                """, slotId.toString(), TENANT_ID.toString(), DOCTOR_ID.toString(), departmentId.toString(),
                LocalDate.now().plusDays(1), new BigDecimal("80.00"));
        jdbcTemplate.update("""
                insert into appointment
                    (id, tenant_id, patient_id, doctor_id, department_id, slot_id,
                     appointment_date, start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '09:00', '10:00', ?, 'COMPLETED',
                        current_timestamp, current_timestamp)
                """, appointmentId.toString(), TENANT_ID.toString(), PATIENT_ID.toString(), DOCTOR_ID.toString(),
                departmentId.toString(), slotId.toString(), LocalDate.now().plusDays(1),
                new BigDecimal("80.00"));
        jdbcTemplate.update("""
                insert into consultation
                    (id, appointment_id, tenant_id, patient_id, doctor_id, complaint, diagnosis,
                     advice, status, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, '主诉', '诊断', '建议', 'COMPLETED',
                        current_timestamp, current_timestamp, current_timestamp)
                """, recordId.toString(), appointmentId.toString(), TENANT_ID.toString(),
                PATIENT_ID.toString(), DOCTOR_ID.toString());
    }

    @AfterEach
    void removeFixture() {
        jdbcTemplate.update("delete from file_metadata where record_id = ?", recordId.toString());
        jdbcTemplate.update("delete from consultation where id = ?", recordId.toString());
        jdbcTemplate.update("delete from appointment where id = ?", appointmentId.toString());
        jdbcTemplate.update("delete from schedule_slot where id = ?", slotId.toString());
        jdbcTemplate.update("delete from department where id = ?", departmentId.toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {1024 * 1024 + 1, TEN_MIB})
    void allowedFilesLargerThanTheFrameworkDefaultReachTheService(int sizeBytes) throws Exception {
        ResponseEntity<String> response = upload(sizeBytes);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(files.lastStoredSize()).isEqualTo(sizeBytes);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.at("/data/sizeBytes").asLong()).isEqualTo(sizeBytes);
        assertThat(jdbcTemplate.queryForObject(
                "select size_bytes from file_metadata where record_id = ?",
                Long.class,
                recordId.toString())).isEqualTo(sizeBytes);
    }

    @Test
    void firstByteOverTenMibReturnsTheStableFileTooLargeContract() throws Exception {
        ResponseEntity<String> response = upload(TEN_MIB + 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo("FILE_TOO_LARGE");
        assertThat(files.lastStoredSize()).isEqualTo(-1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from file_metadata where record_id = ?",
                Integer.class,
                recordId.toString())).isZero();
    }

    private ResponseEntity<String> upload(int sizeBytes) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_PDF);
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(
                new NamedByteArrayResource(new byte[sizeBytes]),
                partHeaders);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        requestHeaders.setBearerAuth(jwtService.issue(doctor()));
        return http.postForEntity(
                "/api/doctor/records/" + recordId + "/files",
                new HttpEntity<>(body, requestHeaders),
                String.class);
    }

    private static CurrentUser doctor() {
        return new CurrentUser(DOCTOR_ID, "doctor_a", UserRole.DOCTOR, TENANT_ID);
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private NamedByteArrayResource(byte[] byteArray) {
            super(byteArray);
        }

        @Override
        public String getFilename() {
            return "scan.pdf";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeStorageConfiguration {
        @Bean
        @Primary
        RecordingFileAccessPort recordingFileAccessPort() {
            return new RecordingFileAccessPort();
        }
    }

    static final class RecordingFileAccessPort implements FileAccessPort {
        private final AtomicLong lastStoredSize = new AtomicLong(-1);

        @Override
        public String store(
                UUID actorId,
                UUID tenantId,
                UUID businessObjectId,
                String fileName,
                InputStream content
        ) {
            try {
                lastStoredSize.set(content.readAllBytes().length);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return tenantId + "/" + businessObjectId + "/" + UUID.randomUUID() + ".pdf";
        }

        @Override
        public InputStream load(UUID actorId, UUID tenantId, UUID businessObjectId, String objectKey) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void delete(String objectKey) {
        }

        @Override
        public String presignGet(String objectKey, Duration ttl) {
            return "https://objects.example/signed";
        }

        long lastStoredSize() {
            return lastStoredSize.get();
        }

        void reset() {
            lastStoredSize.set(-1);
        }
    }
}
