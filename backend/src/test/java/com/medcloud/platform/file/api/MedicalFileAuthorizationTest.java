package com.medcloud.platform.file.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.file.application.FileAccessPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MedicalFileAuthorizationTest {
    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "10000000-0000-0000-0000-000000000001";
    private static final String DOCTOR_A = "20000000-0000-0000-0000-000000000001";
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final FakeFileAccessPort files;

    private UUID recordId;
    private UUID appointmentId;
    private UUID slotId;
    private UUID departmentId;
    private UUID sameTenantDoctorId;
    private UUID otherPatientId;
    private UUID tenantBAdminId;
    private String sameTenantDoctorUsername;
    private String otherPatientUsername;
    private String tenantBAdminUsername;

    @Autowired
    MedicalFileAuthorizationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            FakeFileAccessPort files
    ) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.files = files;
    }

    @BeforeEach
    void createCompletedRecord() {
        recordId = UUID.randomUUID();
        appointmentId = UUID.randomUUID();
        slotId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        sameTenantDoctorId = UUID.randomUUID();
        otherPatientId = UUID.randomUUID();
        tenantBAdminId = UUID.randomUUID();
        sameTenantDoctorUsername = "file_same_doctor_" + compact(sameTenantDoctorId);
        otherPatientUsername = "file_other_patient_" + compact(otherPatientId);
        tenantBAdminUsername = "file_tenant_b_admin_" + compact(tenantBAdminId);
        files.reset();

        insertAccount(sameTenantDoctorId, sameTenantDoctorUsername, "DOCTOR", TENANT_A);
        insertAccount(otherPatientId, otherPatientUsername, "PATIENT", null);
        insertAccount(tenantBAdminId, tenantBAdminUsername, "HOSPITAL_ADMIN", TENANT_B);
        jdbcTemplate.update("""
                insert into department
                    (id, tenant_id, name, description, status, created_at, updated_at)
                values (?, ?, ?, '', 'ACTIVE', current_timestamp, current_timestamp)
                """, departmentId.toString(), TENANT_A, "附件测试科-" + compact(departmentId));
        jdbcTemplate.update("""
                insert into schedule_slot
                    (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                     capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '09:00', '10:00', 1, ?, 'PUBLISHED', 0,
                        current_timestamp, current_timestamp)
                """, slotId.toString(), TENANT_A, DOCTOR_A, departmentId.toString(),
                LocalDate.now().plusDays(1), new BigDecimal("80.00"));
        jdbcTemplate.update("""
                insert into appointment
                    (id, tenant_id, patient_id, doctor_id, department_id, slot_id,
                     appointment_date, start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '09:00', '10:00', ?, 'COMPLETED',
                        current_timestamp, current_timestamp)
                """, appointmentId.toString(), TENANT_A, PATIENT, DOCTOR_A,
                departmentId.toString(), slotId.toString(), LocalDate.now().plusDays(1),
                new BigDecimal("80.00"));
        jdbcTemplate.update("""
                insert into consultation
                    (id, appointment_id, tenant_id, patient_id, doctor_id, complaint, diagnosis,
                     advice, status, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, '主诉', '诊断', '建议', 'COMPLETED',
                        current_timestamp, current_timestamp, current_timestamp)
                """, recordId.toString(), appointmentId.toString(), TENANT_A, PATIENT, DOCTOR_A);
    }

    @AfterEach
    void removeFixture() {
        jdbcTemplate.update("delete from file_metadata where record_id = ?", recordId.toString());
        jdbcTemplate.update("delete from consultation where id = ?", recordId.toString());
        jdbcTemplate.update("delete from appointment where id = ?", appointmentId.toString());
        jdbcTemplate.update("delete from schedule_slot where id = ?", slotId.toString());
        jdbcTemplate.update("delete from department where id = ?", departmentId.toString());
        jdbcTemplate.update("delete from user_account where id in (?, ?, ?)",
                sameTenantDoctorId.toString(), otherPatientId.toString(), tenantBAdminId.toString());
    }

    @Test
    void onlyTheAssignedSameTenantDoctorCanUpload() throws Exception {
        mockMvc.perform(multipart("/api/doctor/records/{recordId}/files", recordId)
                        .file(pdf())
                        .header(AUTHORIZATION, bearer("doctor_a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value(recordId.toString()))
                .andExpect(jsonPath("$.data.originalFilename").value("scan.pdf"))
                .andExpect(jsonPath("$.data.objectKey").doesNotExist());

        for (String username : List.of(
                sameTenantDoctorUsername,
                "doctor_b",
                "patient_demo",
                "hospital_a",
                "platform_admin")) {
            mockMvc.perform(multipart("/api/doctor/records/{recordId}/files", recordId)
                            .file(pdf())
                            .header(AUTHORIZATION, bearer(username)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from file_metadata where record_id = ?",
                Integer.class,
                recordId.toString())).isEqualTo(1);
    }

    @Test
    void metadataListingAllowsOwnersAndSameTenantHospitalAdminWithoutLeakingStorageDetails() throws Exception {
        uploadAsAssignedDoctor();

        for (String username : List.of("doctor_a", "patient_demo", "hospital_a")) {
            mockMvc.perform(get("/api/records/{recordId}/files", recordId)
                            .header(AUTHORIZATION, bearer(username)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].originalFilename").value("scan.pdf"))
                    .andExpect(jsonPath("$.data[0].objectKey").doesNotExist())
                    .andExpect(jsonPath("$.data[0].tenantId").doesNotExist())
                    .andExpect(jsonPath("$.data[0].uploaderId").doesNotExist());
        }

        for (String username : List.of(
                sameTenantDoctorUsername,
                "doctor_b",
                otherPatientUsername,
                tenantBAdminUsername,
                "platform_admin")) {
            mockMvc.perform(get("/api/records/{recordId}/files", recordId)
                            .header(AUTHORIZATION, bearer(username)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        }
    }

    @Test
    void downloadUrlAllowsOnlyTheAssignedDoctorAndPatientOwner() throws Exception {
        UUID fileId = uploadAsAssignedDoctor();

        for (String username : List.of("doctor_a", "patient_demo")) {
            mockMvc.perform(post("/api/files/{fileId}/download-url", fileId)
                            .header(AUTHORIZATION, bearer(username)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.url").value("https://objects.example/signed"))
                    .andExpect(jsonPath("$.data.objectKey").doesNotExist());
        }

        for (String username : List.of(
                sameTenantDoctorUsername,
                "doctor_b",
                otherPatientUsername,
                "hospital_a",
                tenantBAdminUsername,
                "platform_admin")) {
            mockMvc.perform(post("/api/files/{fileId}/download-url", fileId)
                            .header(AUTHORIZATION, bearer(username)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        }

        assertThat(files.presignTtls).containsOnly(Duration.ofMinutes(5));
        assertThat(files.presignTtls).hasSize(2);
    }

    @Test
    void auditsAuthorizedMetadataAndDownloadAccessUsingOnlyRecordAndFileIdentifiers() throws Exception {
        UUID fileId = uploadAsAssignedDoctor();

        MvcResult metadata = mockMvc.perform(get("/api/records/{recordId}/files", recordId)
                        .header(AUTHORIZATION, bearer("hospital_a")))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult download = mockMvc.perform(post("/api/files/{fileId}/download-url", fileId)
                        .header(AUTHORIZATION, bearer("doctor_a")))
                .andExpect(status().isOk())
                .andReturn();

        assertAudit(metadata, "FILE_METADATA_ACCESSED", "CONSULTATION", recordId, "SUCCESS");
        assertAudit(download, "FILE_DOWNLOAD_URL_CREATED", "FILE", fileId, "SUCCESS");
    }

    @Test
    void auditsBusinessRejectedFileAccessButNotRejectionsBeforeTheController() throws Exception {
        UUID fileId = uploadAsAssignedDoctor();
        MvcResult rejectedMetadata = mockMvc.perform(get("/api/records/{recordId}/files", recordId)
                        .header(AUTHORIZATION, bearer(sameTenantDoctorUsername)))
                .andExpect(status().isForbidden())
                .andReturn();
        MvcResult rejectedDownload = mockMvc.perform(post("/api/files/{fileId}/download-url", fileId)
                        .header(AUTHORIZATION, bearer(sameTenantDoctorUsername)))
                .andExpect(status().isForbidden())
                .andReturn();

        assertAudit(rejectedMetadata, "FILE_METADATA_ACCESSED", "CONSULTATION", recordId, "REJECTED");
        assertAudit(rejectedDownload, "FILE_DOWNLOAD_URL_CREATED", "FILE", fileId, "REJECTED");

        int auditsBeforePreAuthorization = countAudits("FILE_METADATA_ACCESSED", recordId);
        mockMvc.perform(get("/api/records/{recordId}/files", recordId)
                        .header(AUTHORIZATION, bearer("platform_admin")))
                .andExpect(status().isForbidden());
        assertThat(countAudits("FILE_METADATA_ACCESSED", recordId)).isEqualTo(auditsBeforePreAuthorization);
    }

    private UUID uploadAsAssignedDoctor() throws Exception {
        String response = mockMvc.perform(multipart("/api/doctor/records/{recordId}/files", recordId)
                        .file(pdf())
                        .header(AUTHORIZATION, bearer("doctor_a")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
    }

    private void assertAudit(
            MvcResult result,
            String action,
            String objectType,
            UUID objectId,
            String outcome
    ) {
        String requestId = result.getResponse().getHeader("X-Request-Id");
        assertThat(jdbcTemplate.queryForObject("""
                        select count(*) from audit_event
                        where request_id = ? and action = ? and object_type = ? and object_id = ? and result = ?
                        """, Integer.class, requestId, action, objectType, objectId.toString(), outcome))
                .isEqualTo(1);
    }

    private int countAudits(String action, UUID objectId) {
        return jdbcTemplate.queryForObject("""
                        select count(*) from audit_event where action = ? and object_id = ?
                        """, Integer.class, action, objectId.toString());
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile(
                "file", "scan.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF".getBytes());
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }

    private void insertAccount(UUID id, String username, String role, String tenantId) {
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, current_timestamp, current_timestamp)
                """, id.toString(), username, DEMO_HASH, role, tenantId);
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeStorageConfiguration {
        @Bean
        @Primary
        FakeFileAccessPort fakeFileAccessPort() {
            return new FakeFileAccessPort();
        }
    }

    static final class FakeFileAccessPort implements FileAccessPort {
        private final List<Duration> presignTtls = new ArrayList<>();

        @Override
        public String store(
                UUID actorId,
                UUID tenantId,
                UUID businessObjectId,
                String fileName,
                InputStream content
        ) {
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
            presignTtls.add(ttl);
            return "https://objects.example/signed";
        }

        void reset() {
            presignTtls.clear();
        }
    }
}
