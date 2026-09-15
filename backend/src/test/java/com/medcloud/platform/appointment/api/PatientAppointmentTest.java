package com.medcloud.platform.appointment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.security.JwtService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PatientAppointmentTest {
    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String DOCTOR_A = "20000000-0000-0000-0000-000000000001";
    private static final String PATIENT_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final JwtService jwtService;

    private String departmentId;

    @Autowired
    PatientAppointmentTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            JwtService jwtService
    ) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.jwtService = jwtService;
    }

    @BeforeEach
    void createDoctorDirectory() {
        departmentId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into department
                    (id, tenant_id, name, description, status, created_at, updated_at)
                values (?, ?, '预约测试科', '', 'ACTIVE', current_timestamp, current_timestamp)
                """, departmentId, TENANT_A);
        jdbcTemplate.update("""
                insert into doctor_profile
                    (user_id, tenant_id, department_id, display_name, title, biography)
                values (?, ?, ?, '预约测试医生', '医师', '')
                """, DOCTOR_A, TENANT_A, departmentId);
    }

    @Test
    void bookingUsesOnlyTheLockedSlotAndAuthenticatedPatientForItsSnapshot() throws Exception {
        LocalDate date = LocalDate.now().plusDays(12);
        String slotId = insertSlot(date, 2, "39.00", "PUBLISHED");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("slotId", slotId);
        request.put("patientId", UUID.randomUUID().toString());
        request.put("tenantId", "22222222-2222-2222-2222-222222222222");
        request.put("doctorId", UUID.randomUUID().toString());
        request.put("departmentId", UUID.randomUUID().toString());
        request.put("date", LocalDate.now().plusYears(1).toString());
        request.put("startTime", "22:00");
        request.put("endTime", "23:00");
        request.put("fee", "0.01");

        String response = mockMvc.perform(post("/api/patient/appointments")
                        .header(AUTHORIZATION, bearer("patient_demo"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.patientId").value(PATIENT_ONE))
                .andExpect(jsonPath("$.data.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.data.doctorId").value(DOCTOR_A))
                .andExpect(jsonPath("$.data.departmentId").value(departmentId))
                .andExpect(jsonPath("$.data.slotId").value(slotId))
                .andExpect(jsonPath("$.data.date").value(date.toString()))
                .andExpect(jsonPath("$.data.startTime").value("09:00:00"))
                .andExpect(jsonPath("$.data.endTime").value("10:00:00"))
                .andExpect(jsonPath("$.data.fee").value(39.00))
                .andExpect(jsonPath("$.data.status").value("BOOKED"))
                .andReturn().getResponse().getContentAsString();

        String appointmentId = objectMapper.readTree(response).at("/data/id").asText();
        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                select patient_id, tenant_id, doctor_id, department_id, slot_id,
                       appointment_date, start_time, end_time, fee, status
                from appointment where id = ?
                """, appointmentId);
        assertThat(stored.get("patient_id").toString()).isEqualTo(PATIENT_ONE);
        assertThat(stored.get("tenant_id").toString()).isEqualTo(TENANT_A);
        assertThat(stored.get("doctor_id").toString()).isEqualTo(DOCTOR_A);
        assertThat(stored.get("department_id").toString()).isEqualTo(departmentId);
        assertThat(stored.get("slot_id").toString()).isEqualTo(slotId);
        assertThat(stored.get("appointment_date").toString()).isEqualTo(date.toString());
        assertThat(stored.get("start_time").toString()).startsWith("09:00");
        assertThat(stored.get("end_time").toString()).startsWith("10:00");
        assertThat(stored.get("fee").toString()).isEqualTo("39.00");
        assertThat(stored.get("status")).isEqualTo("BOOKED");

        jdbcTemplate.update("""
                update schedule_slot
                set schedule_date = ?, start_time = '20:00', end_time = '21:00', fee = 1.00
                where id = ?
                """, date.plusDays(1), slotId);
        entityManager.clear();
        mockMvc.perform(get("/api/patient/appointments/{id}", appointmentId)
                        .header(AUTHORIZATION, bearer("patient_demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(date.toString()))
                .andExpect(jsonPath("$.data.startTime").value("09:00:00"))
                .andExpect(jsonPath("$.data.endTime").value("10:00:00"))
                .andExpect(jsonPath("$.data.fee").value(39.00));
    }

    @Test
    void aPatientCannotHoldTwoActiveBookingsForTheSameSlot() throws Exception {
        String slotId = insertSlot(LocalDate.now().plusDays(13), 2, "49.00", "PUBLISHED");
        String token = bearer("patient_demo");
        book(token, slotId, 200);

        book(token, slotId, 409)
                .andExpect(jsonPath("$.code").value("APPOINTMENT_DUPLICATE"));

        Integer active = jdbcTemplate.queryForObject("""
                select count(*) from appointment
                where patient_id = ? and slot_id = ? and status <> 'CANCELLED'
                """, Integer.class, PATIENT_ONE, slotId);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void aFullSlotRejectsAnotherPatientWithoutOverselling() throws Exception {
        String slotId = insertSlot(LocalDate.now().plusDays(14), 1, "59.00", "PUBLISHED");
        String secondPatient = insertPatient();
        book(bearer("patient_demo"), slotId, 200);

        book(bearer(secondPatient), slotId, 409)
                .andExpect(jsonPath("$.code").value("SCHEDULE_FULL"));

        Integer active = jdbcTemplate.queryForObject(
                "select count(*) from appointment where slot_id = ? and status <> 'CANCELLED'",
                Integer.class, slotId);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void onlyABookedAppointmentCanBeCancelledAndCancellationAllowsRebooking() throws Exception {
        String slotId = insertSlot(LocalDate.now().plusDays(15), 1, "69.00", "PUBLISHED");
        String token = bearer("patient_demo");
        JsonNode booked = responseData(book(token, slotId, 200));

        mockMvc.perform(post("/api/patient/appointments/{id}/cancel", booked.get("id").asText())
                        .header(AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        JsonNode rebooked = responseData(book(token, slotId, 200));
        assertThat(rebooked.get("id").asText()).isNotEqualTo(booked.get("id").asText());
        assertThat(rebooked.get("status").asText()).isEqualTo("BOOKED");

        jdbcTemplate.update("update appointment set status = 'IN_PROGRESS' where id = ?",
                rebooked.get("id").asText());
        entityManager.clear();
        mockMvc.perform(post("/api/patient/appointments/{id}/cancel", rebooked.get("id").asText())
                        .header(AUTHORIZATION, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_STATE_INVALID"));
    }

    @Test
    void patientQueriesAndCancellationAreAlwaysScopedToTheAuthenticatedPatient() throws Exception {
        String slotId = insertSlot(LocalDate.now().plusDays(16), 2, "79.00", "PUBLISHED");
        String patientOneToken = bearer("patient_demo");
        String secondPatient = insertPatient();
        String patientTwoToken = bearer(secondPatient);
        JsonNode patientOneAppointment = responseData(book(patientOneToken, slotId, 200));
        JsonNode patientTwoAppointment = responseData(book(patientTwoToken, slotId, 200));

        mockMvc.perform(get("/api/patient/appointments").header(AUTHORIZATION, patientOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(patientOneAppointment.get("id").asText()))
                .andExpect(jsonPath("$.data[0].patientId").value(PATIENT_ONE));
        mockMvc.perform(get("/api/patient/appointments/{id}", patientOneAppointment.get("id").asText())
                        .header(AUTHORIZATION, patientOneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fee").value(79.00));

        mockMvc.perform(get("/api/patient/appointments/{id}", patientTwoAppointment.get("id").asText())
                        .header(AUTHORIZATION, patientOneToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/api/patient/appointments/{id}/cancel", patientOneAppointment.get("id").asText())
                        .header(AUTHORIZATION, patientTwoToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void doctorQueriesAreScopedToDoctorAndTenantAndPlatformAdminCannotReadAppointments() throws Exception {
        String slotId = insertSlot(LocalDate.now().plusDays(17), 1, "89.00", "PUBLISHED");
        JsonNode appointment = responseData(book(bearer("patient_demo"), slotId, 200));
        String sameTenantOtherDoctor = insertDoctor();

        mockMvc.perform(get("/api/doctor/appointments").header(AUTHORIZATION, bearer("doctor_a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(appointment.get("id").asText()))
                .andExpect(jsonPath("$.data[0].tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.data[0].doctorId").value(DOCTOR_A));
        mockMvc.perform(get("/api/doctor/appointments").header(AUTHORIZATION, bearer("doctor_b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/doctor/appointments").header(AUTHORIZATION, bearer(sameTenantOtherDoctor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        String forgedTenant = "Bearer " + jwtService.issue(new CurrentUser(
                UUID.fromString(DOCTOR_A), "doctor_a", UserRole.DOCTOR,
                UUID.fromString("22222222-2222-2222-2222-222222222222")));
        mockMvc.perform(get("/api/doctor/appointments").header(AUTHORIZATION, forgedTenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        String platform = bearer("platform_admin");
        mockMvc.perform(get("/api/patient/appointments").header(AUTHORIZATION, platform))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mockMvc.perform(get("/api/doctor/appointments").header(AUTHORIZATION, platform))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void catalogRemainingCapacitySubtractsOnlyNonCancelledAppointments() throws Exception {
        LocalDate date = LocalDate.now().plusDays(18);
        String slotId = insertSlot(date, 2, "99.00", "PUBLISHED");
        String token = bearer("patient_demo");
        JsonNode appointment = responseData(book(token, slotId, 200));

        catalog(slotId, date, 1);

        mockMvc.perform(post("/api/patient/appointments/{id}/cancel", appointment.get("id").asText())
                        .header(AUTHORIZATION, token))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        catalog(slotId, date, 2);
    }

    @Test
    void disabledAndPastSlotsCannotBeBooked() throws Exception {
        String disabled = insertSlot(LocalDate.now().plusDays(19), 1, "109.00", "DISABLED");
        String past = insertSlot(LocalDate.now().minusDays(1), 1, "119.00", "PUBLISHED");
        String token = bearer("patient_demo");

        book(token, disabled, 409)
                .andExpect(jsonPath("$.code").value("SCHEDULE_UNAVAILABLE"));
        book(token, past, 409)
                .andExpect(jsonPath("$.code").value("SCHEDULE_UNAVAILABLE"));
    }

    @Test
    void databaseConstraintRejectsASecondActiveBookingForTheSamePatientAndSlot() throws Exception {
        LocalDate date = LocalDate.now().plusDays(21);
        String slotId = insertSlot(date, 2, "129.00", "PUBLISHED");
        book(bearer("patient_demo"), slotId, 200);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into appointment
                    (id, tenant_id, patient_id, doctor_id, department_id, slot_id,
                     appointment_date, start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '09:00', '10:00', 129.00, 'BOOKED',
                        current_timestamp, current_timestamp)
                """, UUID.randomUUID().toString(), TENANT_A, PATIENT_ONE, DOCTOR_A, departmentId,
                slotId, date))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(exception.getMessage())
                        .containsIgnoringCase("uk_appointment_active_booking"));
    }

    private void catalog(String slotId, LocalDate date, int expectedRemaining) throws Exception {
        mockMvc.perform(get("/api/catalog/schedule-slots")
                        .param("tenantId", TENANT_A)
                        .param("departmentId", departmentId)
                        .param("date", date.toString())
                        .header(AUTHORIZATION, bearer("patient_demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(slotId))
                .andExpect(jsonPath("$.data.content[0].capacity").value(2))
                .andExpect(jsonPath("$.data.content[0].remainingCapacity").value(expectedRemaining));
    }

    private org.springframework.test.web.servlet.ResultActions book(
            String token,
            String slotId,
            int expectedStatus
    ) throws Exception {
        return mockMvc.perform(post("/api/patient/appointments")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("slotId", slotId))))
                .andExpect(status().is(expectedStatus));
    }

    private JsonNode responseData(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private String insertSlot(LocalDate date, int capacity, String fee, String status) {
        String slotId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into schedule_slot
                    (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                     capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '09:00', '10:00', ?, ?, ?, 0, current_timestamp, current_timestamp)
                """, slotId, TENANT_A, DOCTOR_A, departmentId, date, capacity, fee, status);
        return slotId;
    }

    private String insertPatient() {
        String patientId = UUID.randomUUID().toString();
        String username = "patient_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, 'PATIENT', 'ACTIVE', null, current_timestamp, current_timestamp)
                """, patientId, username, DEMO_HASH);
        return username;
    }

    private String insertDoctor() {
        String doctorId = UUID.randomUUID().toString();
        String username = "doctor_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, 'DOCTOR', 'ACTIVE', ?, current_timestamp, current_timestamp)
                """, doctorId, username, DEMO_HASH, TENANT_A);
        jdbcTemplate.update("""
                insert into doctor_profile
                    (user_id, tenant_id, department_id, display_name, title, biography)
                values (?, ?, ?, '同租户其他医生', '医师', '')
                """, doctorId, TENANT_A, departmentId);
        return username;
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
