package com.medcloud.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Task10PersistentReadApiTest {
    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
    private static final String DOCTOR_A = "20000000-0000-0000-0000-000000000001";
    private static final String DOCTOR_B = "20000000-0000-0000-0000-000000000002";
    private static final String PATIENT = "10000000-0000-0000-0000-000000000001";
    private static final String DEPARTMENT_A = "61000000-0000-0000-0000-000000000001";
    private static final String DEPARTMENT_A_DISABLED = "61000000-0000-0000-0000-000000000002";
    private static final String DEPARTMENT_B = "62000000-0000-0000-0000-000000000001";
    private static final String SLOT_A = "71000000-0000-0000-0000-000000000001";
    private static final String SLOT_B = "72000000-0000-0000-0000-000000000001";
    private static final String APPOINTMENT_A = "81000000-0000-0000-0000-000000000001";
    private static final String APPOINTMENT_B = "82000000-0000-0000-0000-000000000001";
    private static final String RECORD_A = "91000000-0000-0000-0000-000000000001";
    private static final String RECORD_B = "92000000-0000-0000-0000-000000000001";
    private static final String ADMIN_B = "33000000-0000-0000-0000-000000000001";
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    @Autowired
    Task10PersistentReadApiTest(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void insertPersistentFixtures() {
        jdbc.update("insert into department (id, tenant_id, name, description, status) values (?, ?, ?, '', 'ACTIVE')",
                DEPARTMENT_A, TENANT_A, "全科医学科");
        jdbc.update("insert into department (id, tenant_id, name, description, status) values (?, ?, ?, '', 'DISABLED')",
                DEPARTMENT_A_DISABLED, TENANT_A, "停用科室");
        jdbc.update("insert into department (id, tenant_id, name, description, status) values (?, ?, ?, '', 'ACTIVE')",
                DEPARTMENT_B, TENANT_B, "租户B科室");
        jdbc.update("insert into doctor_profile (user_id, tenant_id, department_id, display_name, title, biography) values (?, ?, ?, ?, '医师', '')",
                DOCTOR_A, TENANT_A, DEPARTMENT_A, "演示医生A");
        jdbc.update("insert into doctor_profile (user_id, tenant_id, department_id, display_name, title, biography) values (?, ?, ?, ?, '医师', '')",
                DOCTOR_B, TENANT_B, DEPARTMENT_B, "演示医生B");

        LocalDate day = LocalDate.now().plusDays(7);
        insertSlot(SLOT_A, TENANT_A, DOCTOR_A, DEPARTMENT_A, day, "DISABLED");
        insertSlot(SLOT_B, TENANT_B, DOCTOR_B, DEPARTMENT_B, day, "PUBLISHED");
        insertAppointment(APPOINTMENT_A, TENANT_A, DOCTOR_A, DEPARTMENT_A, SLOT_A, day);
        insertAppointment(APPOINTMENT_B, TENANT_B, DOCTOR_B, DEPARTMENT_B, SLOT_B, day);
        insertConsultation(RECORD_A, APPOINTMENT_A, TENANT_A, DOCTOR_A, "IN_PROGRESS", "本人草稿");
        insertConsultation(RECORD_B, APPOINTMENT_B, TENANT_B, DOCTOR_B, "COMPLETED", "他院病历");

        jdbc.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, 'hospital_b_persisted', ?, 'HOSPITAL_ADMIN', 'ACTIVE', ?, current_timestamp, current_timestamp)
                """, ADMIN_B, DEMO_HASH, TENANT_B);
    }

    @Test
    void catalogDirectoriesExposeOnlyActiveTenantsAndDepartments() throws Exception {
        String token = bearer("patient_demo");
        mockMvc.perform(get("/api/catalog/tenants").header(AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + TENANT_A + "')].name").value("仁和云诊所"));

        mockMvc.perform(get("/api/catalog/tenants/{tenantId}/departments", TENANT_A)
                        .header(AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(DEPARTMENT_A))
                .andExpect(jsonPath("$.data[0].name").value("全科医学科"));
    }

    @Test
    void doctorCanRestoreOnlyOwnTenantRecordsAndDrafts() throws Exception {
        String token = bearer("doctor_a");
        mockMvc.perform(get("/api/doctor/records").header(AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(RECORD_A))
                .andExpect(jsonPath("$.data[0].complaint").value("本人草稿"));

        mockMvc.perform(get("/api/doctor/records/{recordId}", RECORD_B).header(AUTHORIZATION, token))
                .andExpect(status().isNotFound());
    }

    @Test
    void hospitalListsCompleteOwnHistoryAndCanDisableButNotCrossTenantSlot() throws Exception {
        String token = bearer("hospital_a");
        mockMvc.perform(get("/api/hospital/schedule-slots").header(AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(SLOT_A))
                .andExpect(jsonPath("$.data[0].status").value("DISABLED"));

        mockMvc.perform(put("/api/hospital/schedule-slots/{slotId}", SLOT_A)
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(put("/api/hospital/schedule-slots/{slotId}", SLOT_B)
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformRestoresPersistedHospitalAdministratorsWithoutMedicalFields() throws Exception {
        mockMvc.perform(get("/api/platform/tenants/{tenantId}/administrators", TENANT_B)
                        .header(AUTHORIZATION, bearer("platform_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(ADMIN_B))
                .andExpect(jsonPath("$.data[0].username").value("hospital_b_persisted"))
                .andExpect(jsonPath("$.data[0].diagnosis").doesNotExist())
                .andExpect(jsonPath("$.data[0].prescriptionItems").doesNotExist());
    }

    private void insertSlot(String id, String tenantId, String doctorId, String departmentId,
                            LocalDate date, String status) {
        jdbc.update("""
                insert into schedule_slot
                    (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                     capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '09:00:00', '10:00:00', 2, 39.00, ?, 0, current_timestamp, current_timestamp)
                """, id, tenantId, doctorId, departmentId, date, status);
    }

    private void insertAppointment(String id, String tenantId, String doctorId, String departmentId,
                                   String slotId, LocalDate date) {
        jdbc.update("""
                insert into appointment
                    (id, tenant_id, patient_id, doctor_id, department_id, slot_id, appointment_date,
                     start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '09:00:00', '10:00:00', 39.00, 'IN_PROGRESS', current_timestamp, current_timestamp)
                """, id, tenantId, PATIENT, doctorId, departmentId, slotId, date);
    }

    private void insertConsultation(String id, String appointmentId, String tenantId, String doctorId,
                                    String status, String complaint) {
        jdbc.update("""
                insert into consultation
                    (id, appointment_id, tenant_id, patient_id, doctor_id, complaint, diagnosis, advice,
                     status, draft_saved_at, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, '诊断', '建议', ?, current_timestamp,
                        case when ? = 'COMPLETED' then current_timestamp else null end,
                        current_timestamp, current_timestamp)
                """, id, appointmentId, tenantId, PATIENT, doctorId, complaint, status, status);
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }
}
