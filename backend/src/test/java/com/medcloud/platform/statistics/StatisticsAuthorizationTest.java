package com.medcloud.platform.statistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.statistics.application.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
class StatisticsAuthorizationTest {
    @Autowired
    private StatisticsService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    private UUID tenantA;
    private UUID tenantB;
    private UUID tenantC;
    private LocalDate from;
    private LocalDate to;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        tenantC = UUID.randomUUID();
        from = LocalDate.now().minusDays(2);
        to = LocalDate.now();
        insertTenant(tenantA, "统计医院 A", "ACTIVE");
        insertTenant(tenantB, "统计医院 B", "ACTIVE");
        insertTenant(tenantC, "统计医院 C", "DISABLED");
        insertAppointment(tenantA, from, "COMPLETED");
        insertAppointment(tenantA, to, "BOOKED");
        insertAppointment(tenantA, to, "CANCELLED");
        insertAppointment(tenantB, to, "COMPLETED");
        insertCompletedConsultation(tenantA, from);
        insertCompletedConsultation(tenantB, to);
        insertPaidOrder(tenantA, new BigDecimal("78.00"), to);
        insertUnpaidOrder(tenantA, new BigDecimal("500.00"), to);
        insertPaidOrder(tenantB, new BigDecimal("99.00"), to);
    }

    @Test
    void hospitalAdministratorReceivesOnlyOwnTenantInclusiveAggregate() {
        CurrentUser hospitalA = new CurrentUser(UUID.randomUUID(), "hospital-a", UserRole.HOSPITAL_ADMIN, tenantA);

        StatisticsService.HospitalStatisticsResponse result = service.hospital(hospitalA, from, to);

        assertThat(result.tenantId()).isEqualTo(tenantA);
        assertThat(result.totalAppointmentCount()).isEqualTo(6);
        assertThat(result.cancelledAppointmentCount()).isEqualTo(1);
        assertThat(result.completedAppointmentCount()).isEqualTo(4);
        assertThat(result.doctorCompletedConsultationCount()).isEqualTo(3);
        assertThat(result.paidRevenue()).isEqualByComparingTo("78.00");
    }

    @Test
    void platformAdministratorReceivesTenantGroupedSanitizedTotalsOnly() throws Exception {
        CurrentUser platform = new CurrentUser(UUID.randomUUID(), "platform", UserRole.PLATFORM_ADMIN, null);

        StatisticsService.PlatformStatisticsResponse result = service.platform(platform, from, to);
        String serialized = objectMapper.writeValueAsString(result);

        StatisticsService.TenantStatisticsResponse tenantAStatistics = tenantStatistics(result, tenantA);
        StatisticsService.TenantStatisticsResponse tenantBStatistics = tenantStatistics(result, tenantB);
        StatisticsService.TenantStatisticsResponse tenantCStatistics = tenantStatistics(result, tenantC);
        assertThat(tenantAStatistics.accountCount()).isEqualTo(6);
        assertThat(tenantAStatistics.appointmentCount()).isEqualTo(6);
        assertThat(tenantAStatistics.completedAppointmentCount()).isEqualTo(4);
        assertThat(tenantAStatistics.paidRevenue()).isEqualByComparingTo("78.00");
        assertThat(tenantAStatistics.tenantStatus()).isEqualTo("ACTIVE");
        assertThat(tenantBStatistics.accountCount()).isEqualTo(3);
        assertThat(tenantBStatistics.appointmentCount()).isEqualTo(3);
        assertThat(tenantBStatistics.completedAppointmentCount()).isEqualTo(3);
        assertThat(tenantBStatistics.paidRevenue()).isEqualByComparingTo("99.00");
        assertThat(tenantBStatistics.tenantStatus()).isEqualTo("ACTIVE");
        assertThat(tenantCStatistics.accountCount()).isZero();
        assertThat(tenantCStatistics.appointmentCount()).isZero();
        assertThat(tenantCStatistics.completedAppointmentCount()).isZero();
        assertThat(tenantCStatistics.paidRevenue()).isEqualByComparingTo("0.00");
        assertThat(tenantCStatistics.tenantStatus()).isEqualTo("DISABLED");
        assertThat(serialized).doesNotContain("patient", "consultationId", "diagnosis", "name");
    }

    @Test
    void authorizationAndInclusiveWindowAreValidated() {
        CurrentUser patient = new CurrentUser(UUID.randomUUID(), "patient", UserRole.PATIENT, null);
        CurrentUser hospitalA = new CurrentUser(UUID.randomUUID(), "hospital-a", UserRole.HOSPITAL_ADMIN, tenantA);

        assertThatThrownBy(() -> service.hospital(patient, from, to))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
        assertThatThrownBy(() -> service.hospital(hospitalA, from, from.plusDays(366)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void platformStatisticsEndpointRejectsHospitalAdministrators() throws Exception {
        mockMvc.perform(get("/api/platform/statistics")
                        .header(AUTHORIZATION, bearer("hospital_a"))
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    private StatisticsService.TenantStatisticsResponse tenantStatistics(
            StatisticsService.PlatformStatisticsResponse result,
            UUID tenantId
    ) {
        return result.tenants().stream()
                .filter(statistics -> statistics.tenantId().equals(tenantId))
                .findFirst()
                .orElseThrow();
    }

    private void insertTenant(UUID tenantId, String name, String status) {
        jdbcTemplate.update("""
                insert into tenant (id, name, status, created_at, updated_at)
                values (?, ?, ?, current_timestamp, current_timestamp)
                """, tenantId.toString(), name, status);
    }

    private void insertAppointment(UUID tenantId, LocalDate date, String status) {
        UUID patientId = insertAccount("PATIENT", null);
        UUID doctorId = insertAccount("DOCTOR", tenantId);
        UUID departmentId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into department (id, tenant_id, name, description, status, created_at, updated_at)
                values (?, ?, ?, '', 'ACTIVE', current_timestamp, current_timestamp)
                """, departmentId.toString(), tenantId.toString(), "统计科室-" + departmentId);
        jdbcTemplate.update("""
                insert into schedule_slot (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                                           capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '09:00', '10:00', 3, 20.00, 'PUBLISHED', 0, current_timestamp, current_timestamp)
                """, slotId.toString(), tenantId.toString(), doctorId.toString(), departmentId.toString(), date);
        jdbcTemplate.update("""
                insert into appointment (id, tenant_id, patient_id, doctor_id, department_id, slot_id, appointment_date,
                                         start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '09:00', '10:00', 20.00, ?, current_timestamp, current_timestamp)
                """, UUID.randomUUID().toString(), tenantId.toString(), patientId.toString(), doctorId.toString(),
                departmentId.toString(), slotId.toString(), date, status);
    }

    private void insertCompletedConsultation(UUID tenantId, LocalDate completedDate) {
        UUID patientId = insertAccount("PATIENT", null);
        UUID doctorId = insertAccount("DOCTOR", tenantId);
        UUID departmentId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into department (id, tenant_id, name, description, status, created_at, updated_at)
                values (?, ?, ?, '', 'ACTIVE', current_timestamp, current_timestamp)
                """, departmentId.toString(), tenantId.toString(), "完成科室-" + departmentId);
        jdbcTemplate.update("""
                insert into schedule_slot (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                                           capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '10:00', '11:00', 3, 20.00, 'PUBLISHED', 0, current_timestamp, current_timestamp)
                """, slotId.toString(), tenantId.toString(), doctorId.toString(), departmentId.toString(), completedDate);
        jdbcTemplate.update("""
                insert into appointment (id, tenant_id, patient_id, doctor_id, department_id, slot_id, appointment_date,
                                         start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '10:00', '11:00', 20.00, 'COMPLETED', current_timestamp, current_timestamp)
                """, appointmentId.toString(), tenantId.toString(), patientId.toString(), doctorId.toString(),
                departmentId.toString(), slotId.toString(), completedDate);
        jdbcTemplate.update("""
                insert into consultation (id, appointment_id, tenant_id, patient_id, doctor_id, complaint, diagnosis, advice,
                                          status, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, '', '', '', 'COMPLETED', ?, current_timestamp, current_timestamp)
                """, UUID.randomUUID().toString(), appointmentId.toString(), tenantId.toString(), patientId.toString(),
                doctorId.toString(), completedDate.atStartOfDay());
    }

    private void insertPaidOrder(UUID tenantId, BigDecimal amount, LocalDate orderDate) {
        insertOrder(tenantId, amount, orderDate, "PAID");
    }

    private void insertUnpaidOrder(UUID tenantId, BigDecimal amount, LocalDate orderDate) {
        insertOrder(tenantId, amount, orderDate, "PENDING");
    }

    private void insertOrder(UUID tenantId, BigDecimal amount, LocalDate orderDate, String status) {
        UUID patientId = insertAccount("PATIENT", null);
        UUID doctorId = insertAccount("DOCTOR", tenantId);
        UUID departmentId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID consultationId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into department (id, tenant_id, name, description, status, created_at, updated_at)
                values (?, ?, ?, '', 'ACTIVE', current_timestamp, current_timestamp)
                """, departmentId.toString(), tenantId.toString(), "支付科室-" + departmentId);
        jdbcTemplate.update("""
                insert into schedule_slot (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                                           capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '11:00', '12:00', 3, 20.00, 'PUBLISHED', 0, current_timestamp, current_timestamp)
                """, slotId.toString(), tenantId.toString(), doctorId.toString(), departmentId.toString(), orderDate);
        jdbcTemplate.update("""
                insert into appointment (id, tenant_id, patient_id, doctor_id, department_id, slot_id, appointment_date,
                                         start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '11:00', '12:00', 20.00, 'COMPLETED', current_timestamp, current_timestamp)
                """, appointmentId.toString(), tenantId.toString(), patientId.toString(), doctorId.toString(),
                departmentId.toString(), slotId.toString(), orderDate);
        jdbcTemplate.update("""
                insert into consultation (id, appointment_id, tenant_id, patient_id, doctor_id, complaint, diagnosis, advice,
                                          status, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, '', '', '', 'COMPLETED', ?, current_timestamp, current_timestamp)
                """, consultationId.toString(), appointmentId.toString(), tenantId.toString(), patientId.toString(),
                doctorId.toString(), orderDate.atStartOfDay());
        jdbcTemplate.update("""
                insert into medical_order (id, consultation_id, appointment_id, tenant_id, patient_id, amount, status,
                                           transaction_no, paid_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp)
                """, UUID.randomUUID().toString(), consultationId.toString(), appointmentId.toString(), tenantId.toString(),
                patientId.toString(), amount, status,
                "PAID".equals(status) ? "PAY-" + UUID.randomUUID() : null,
                "PAID".equals(status) ? orderDate.atStartOfDay() : null);
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }

    private UUID insertAccount(String role, UUID tenantId) {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into user_account (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, current_timestamp, current_timestamp)
                """, accountId.toString(), "stats_" + accountId, "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2",
                role, tenantId == null ? null : tenantId.toString());
        return accountId;
    }

}
