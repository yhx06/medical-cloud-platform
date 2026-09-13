package com.medcloud.platform.schedule.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.schedule.domain.ScheduleSlot;
import com.medcloud.platform.schedule.infrastructure.ScheduleSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HospitalScheduleTest {
    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ScheduleSlotRepository slots;

    @Autowired
    HospitalScheduleTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            ScheduleSlotRepository slots
    ) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.slots = slots;
    }

    @BeforeEach
    void createSecondHospitalAdministrator() {
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, 'HOSPITAL_ADMIN', 'ACTIVE', ?, current_timestamp, current_timestamp)
                """,
                "30000000-0000-0000-0000-000000000002", "hospital_b", DEMO_HASH, TENANT_B);
    }

    @Test
    void publishedSlotDerivesOwnershipAndPersistsAppointmentSnapshotValues() throws Exception {
        String token = bearer("hospital_a");
        String departmentId = createDepartment(token, "心内科");
        String doctorId = createDoctor(token, departmentId, "李医生");
        LocalDate date = LocalDate.now().plusDays(10);
        Map<String, Object> body = validSlot(doctorId, date, "09:00", "10:00");
        body.put("tenantId", TENANT_B);
        body.put("departmentId", UUID.randomUUID().toString());
        body.put("remainingCapacity", 999);

        String response = mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.data.departmentId").value(departmentId))
                .andExpect(jsonPath("$.data.doctorId").value(doctorId))
                .andExpect(jsonPath("$.data.doctorName").value("李医生"))
                .andExpect(jsonPath("$.data.capacity").value(2))
                .andExpect(jsonPath("$.data.remainingCapacity").value(2))
                .andExpect(jsonPath("$.data.fee").value(39.00))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();

        UUID slotId = UUID.fromString(objectMapper.readTree(response).at("/data/id").asText());
        ScheduleSlot stored = slots.findById(slotId).orElseThrow();
        assertThat(stored.getTenantId()).isEqualTo(UUID.fromString(TENANT_A));
        assertThat(stored.getDepartmentId()).isEqualTo(UUID.fromString(departmentId));
        assertThat(stored.getDoctorId()).isEqualTo(UUID.fromString(doctorId));
        assertThat(stored.getScheduleDate()).isEqualTo(date);
        assertThat(stored.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(stored.getEndTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(stored.getCapacity()).isEqualTo(2);
        assertThat(stored.getFee()).isEqualByComparingTo(new BigDecimal("39.00"));
    }

    @Test
    void todaysSlotWithMinimumCapacityAndZeroFeeIsAccepted() throws Exception {
        SlotFixture fixture = fixture("边界值医生");
        Map<String, Object> body = validSlot(fixture.doctorId(), LocalDate.now(), "09:00", "10:00");
        body.put("capacity", 1);
        body.put("fee", "0.00");

        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacity").value(1))
                .andExpect(jsonPath("$.data.remainingCapacity").value(1))
                .andExpect(jsonPath("$.data.fee").value(0.00));
    }

    @Test
    void publishingAPastDateReturnsValidationError() throws Exception {
        SlotFixture fixture = fixture("过期号源医生");

        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSlot(
                                fixture.doctorId(), LocalDate.now().minusDays(1), "09:00", "10:00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void publishingNonPositiveCapacityReturnsValidationError(int capacity) throws Exception {
        SlotFixture fixture = fixture("容量校验医生" + Math.abs(capacity));
        Map<String, Object> body = validSlot(
                fixture.doctorId(), LocalDate.now().plusDays(10), "09:00", "10:00");
        body.put("capacity", capacity);

        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void publishingNegativeFeeReturnsValidationError() throws Exception {
        SlotFixture fixture = fixture("诊费校验医生");
        Map<String, Object> body = validSlot(
                fixture.doctorId(), LocalDate.now().plusDays(10), "09:00", "10:00");
        body.put("fee", "-0.01");

        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @CsvSource({"09:00,09:00", "10:00,09:00"})
    void publishingAReversedOrEmptyTimeRangeReturnsValidationError(String start, String end) throws Exception {
        SlotFixture fixture = fixture("时段校验医生" + start.replace(":", ""));

        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSlot(
                                fixture.doctorId(), LocalDate.now().plusDays(10), start, end))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void disabledDoctorCannotReceiveNewSlots() throws Exception {
        SlotFixture fixture = fixture("停用医生");
        mockMvc.perform(put("/api/hospital/doctors/{id}/status", fixture.doctorId())
                        .header(AUTHORIZATION, fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSlot(
                                fixture.doctorId(), LocalDate.now().plusDays(10), "09:00", "10:00"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_UNAVAILABLE"));
    }

    @Test
    void disabledDepartmentCannotReceiveNewSlots() throws Exception {
        SlotFixture fixture = fixture("停用科室医生");
        mockMvc.perform(put("/api/hospital/departments/{id}", fixture.departmentId())
                        .header(AUTHORIZATION, fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", fixture.departmentName(),
                                "description", "",
                                "status", "DISABLED"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, fixture.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSlot(
                                fixture.doctorId(), LocalDate.now().plusDays(10), "09:00", "10:00"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_UNAVAILABLE"));
    }

    @Test
    void overlappingPublishedSlotIsRejectedWhileTouchingBoundaryIsAllowed() throws Exception {
        SlotFixture fixture = fixture("重叠校验医生");
        LocalDate date = LocalDate.now().plusDays(10);
        publish(fixture.token(), validSlot(fixture.doctorId(), date, "09:00", "10:00"), 200);

        publish(fixture.token(), validSlot(fixture.doctorId(), date, "09:30", "10:30"), 409)
                .andExpect(jsonPath("$.code").value("SCHEDULE_OVERLAP"));

        publish(fixture.token(), validSlot(fixture.doctorId(), date, "10:00", "11:00"), 200)
                .andExpect(jsonPath("$.data.startTime").value("10:00:00"));
    }

    @Test
    void hospitalCannotPublishSlotForAnotherTenantsDoctor() throws Exception {
        String hospitalB = bearer("hospital_b");
        String departmentB = createDepartment(hospitalB, "租户B排班科室");
        String doctorB = createDoctor(hospitalB, departmentB, "租户B医生");

        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, bearer("hospital_a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSlot(
                                doctorB, LocalDate.now().plusDays(10), "09:00", "10:00"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"patient_demo", "doctor_a", "platform_admin"})
    void nonHospitalRolesCannotPublishSlots(String username) throws Exception {
        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, bearer(username))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSlot(
                                UUID.randomUUID().toString(), LocalDate.now().plusDays(10), "09:00", "10:00"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void anonymousUserCannotPublishSlots() throws Exception {
        mockMvc.perform(post("/api/hospital/schedule-slots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSlot(
                                UUID.randomUUID().toString(), LocalDate.now().plusDays(10), "09:00", "10:00"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    private SlotFixture fixture(String doctorName) throws Exception {
        String token = bearer("hospital_a");
        String departmentName = "排班测试科室" + UUID.randomUUID().toString().substring(0, 6);
        String departmentId = createDepartment(token, departmentName);
        String doctorId = createDoctor(token, departmentId, doctorName);
        return new SlotFixture(token, departmentId, doctorId, departmentName);
    }

    private org.springframework.test.web.servlet.ResultActions publish(
            String token,
            Map<String, Object> body,
            int expectedStatus
    ) throws Exception {
        return mockMvc.perform(post("/api/hospital/schedule-slots")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus));
    }

    private Map<String, Object> validSlot(
            String doctorId,
            LocalDate date,
            String startTime,
            String endTime
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("doctorId", doctorId);
        body.put("date", date.toString());
        body.put("startTime", startTime);
        body.put("endTime", endTime);
        body.put("capacity", 2);
        body.put("fee", "39.00");
        return body;
    }

    private String createDepartment(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/hospital/departments")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "description", ""))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/id").asText();
    }

    private String createDoctor(String token, String departmentId, String displayName) throws Exception {
        String username = "doctor_" + UUID.randomUUID().toString().replace("-", "");
        String response = mockMvc.perform(post("/api/hospital/doctors")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "Doctor@123456",
                                "departmentId", departmentId,
                                "displayName", displayName,
                                "title", "医师",
                                "biography", ""))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.at("/data/id").asText();
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }

    private record SlotFixture(String token, String departmentId, String doctorId, String departmentName) {
    }
}
