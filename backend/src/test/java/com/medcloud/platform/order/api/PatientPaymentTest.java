package com.medcloud.platform.order.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.order.application.MedicalOrderService;
import com.medcloud.platform.order.application.MedicalOrderService.MedicalOrderResponse;
import com.medcloud.platform.order.domain.OrderStatus;
import com.medcloud.platform.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PatientPaymentTest {
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final MedicalOrderService orderService;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    private UUID tenantId;
    private UUID patientId;
    private UUID doctorId;
    private UUID departmentId;
    private UUID slotId;
    private UUID appointmentId;
    private UUID consultationId;
    private UUID orderId;
    private String patientUsername;

    @Autowired
    PatientPaymentTest(
            MedicalOrderService orderService,
            TransactionTemplate transactions,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc,
            ObjectMapper objectMapper
    ) {
        this.orderService = orderService;
        this.transactions = transactions;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @BeforeEach
    void createPendingOrderFixture() {
        tenantId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        doctorId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        slotId = UUID.randomUUID();
        appointmentId = UUID.randomUUID();
        consultationId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        patientUsername = "payment_patient_" + patientId.toString().replace("-", "");
        transactions.executeWithoutResult(status -> insertFixture());
    }

    @AfterEach
    void removeFixture() {
        if (tenantId != null) {
            jdbcTemplate.update("delete from medical_order where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from consultation where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from appointment where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from schedule_slot where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from doctor_profile where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from user_account where username like 'payment_%'");
            jdbcTemplate.update("delete from department where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from tenant where id = ?", tenantId.toString());
        }
    }

    @Test
    void firstPaymentPersistsAReceiptAndRetryReturnsTheOriginalReceipt() {
        MedicalOrderResponse first = orderService.pay(patient(), orderId);
        MedicalOrderResponse second = orderService.pay(patient(), orderId);

        assertThat(first.status()).isEqualTo(OrderStatus.PAID);
        assertThat(first.transactionNo()).isNotBlank();
        assertThat(first.paidAt()).isNotNull();
        assertThat(second.transactionNo()).isEqualTo(first.transactionNo());
        assertThat(second.paidAt()).isEqualTo(first.paidAt());
        assertThat(jdbcTemplate.queryForMap(
                "select status, transaction_no, paid_at from medical_order where id = ?",
                orderId.toString()))
                .containsEntry("status", "PAID")
                .containsEntry("transaction_no", first.transactionNo());
    }

    @Test
    void concurrentPaymentsUseIndependentTransactionsAndReturnOneReceipt() throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MedicalOrderResponse> first = executor.submit(() -> pay(startTogether));
            Future<MedicalOrderResponse> second = executor.submit(() -> pay(startTogether));

            MedicalOrderResponse firstPayment = first.get(10, TimeUnit.SECONDS);
            MedicalOrderResponse secondPayment = second.get(10, TimeUnit.SECONDS);
            assertThat(secondPayment.transactionNo()).isEqualTo(firstPayment.transactionNo());
            assertThat(secondPayment.paidAt()).isEqualTo(firstPayment.paidAt());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(distinct transaction_no) from medical_order where id = ?",
                    Integer.class,
                    orderId.toString())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anotherPatientCannotPayTheOrder() {
        CurrentUser anotherPatient = new CurrentUser(
                UUID.randomUUID(), "another-patient", UserRole.PATIENT, null);

        assertThatThrownBy(() -> orderService.pay(anotherPatient, orderId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));

        assertThat(jdbcTemplate.queryForMap(
                "select status, transaction_no, paid_at from medical_order where id = ?",
                orderId.toString()))
                .containsEntry("status", "PENDING")
                .containsEntry("transaction_no", null)
                .containsEntry("paid_at", null);
    }

    @Test
    void cancelledOrderCannotBePaid() {
        jdbcTemplate.update("update medical_order set status = 'CANCELLED' where id = ?", orderId.toString());

        assertThatThrownBy(() -> orderService.pay(patient(), orderId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ORDER_STATE_INVALID));

        assertThat(jdbcTemplate.queryForMap(
                "select status, transaction_no, paid_at from medical_order where id = ?",
                orderId.toString()))
                .containsEntry("status", "CANCELLED")
                .containsEntry("transaction_no", null)
                .containsEntry("paid_at", null);
    }

    @Test
    void patientEndpointsExposeOnlyOwnedOrdersAndIgnoreForgedPaymentFields() throws Exception {
        OtherOrder other = insertOtherPatientOrder();
        String patientToken = bearer(patientUsername);
        String otherPatientToken = bearer(other.patientUsername());

        mockMvc.perform(get("/api/patient/orders").header(AUTHORIZATION, patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(orderId.toString()))
                .andExpect(jsonPath("$.data[0].amount").value(39.00))
                .andExpect(jsonPath("$.data[0].patientId").doesNotExist())
                .andExpect(jsonPath("$.data[0].tenantId").doesNotExist());
        mockMvc.perform(get("/api/patient/orders/{id}", orderId)
                        .header(AUTHORIZATION, patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId.toString()))
                .andExpect(jsonPath("$.data.patientId").doesNotExist())
                .andExpect(jsonPath("$.data.tenantId").doesNotExist());

        Map<String, Object> forgedPayment = Map.of(
                "amount", "0.01",
                "patientId", other.patientId().toString(),
                "tenantId", UUID.randomUUID().toString());
        mockMvc.perform(post("/api/patient/orders/{id}/pay", orderId)
                        .header(AUTHORIZATION, patientToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(forgedPayment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(39.00))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.transactionNo").isNotEmpty())
                .andExpect(jsonPath("$.data.patientId").doesNotExist())
                .andExpect(jsonPath("$.data.tenantId").doesNotExist());
        assertThat(jdbcTemplate.queryForMap(
                "select patient_id, tenant_id, amount from medical_order where id = ?",
                orderId.toString()))
                .containsEntry("patient_id", patientId.toString())
                .containsEntry("tenant_id", tenantId.toString());
        assertThat((BigDecimal) jdbcTemplate.queryForMap(
                "select amount from medical_order where id = ?", orderId.toString()).get("amount"))
                .isEqualByComparingTo("39.00");

        mockMvc.perform(post("/api/patient/orders/{id}/pay", orderId)
                        .header(AUTHORIZATION, otherPatientToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mockMvc.perform(get("/api/patient/orders/{id}", orderId)
                        .header(AUTHORIZATION, otherPatientToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void hospitalAndPlatformAdministratorsCannotReadPatientOrders() throws Exception {
        for (String administrator : List.of("hospital_a", "platform_admin")) {
            String token = bearer(administrator);
            mockMvc.perform(get("/api/patient/orders").header(AUTHORIZATION, token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
            mockMvc.perform(get("/api/patient/orders/{id}", orderId)
                            .header(AUTHORIZATION, token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        }
    }

    private MedicalOrderResponse pay(CyclicBarrier startTogether) throws Exception {
        startTogether.await(5, TimeUnit.SECONDS);
        return orderService.pay(patient(), orderId);
    }

    private CurrentUser patient() {
        return new CurrentUser(patientId, patientUsername, UserRole.PATIENT, null);
    }

    private void insertFixture() {
        jdbcTemplate.update("""
                insert into tenant (id, name, status, created_at, updated_at)
                values (?, ?, 'ACTIVE', current_timestamp, current_timestamp)
                """, tenantId.toString(), "支付测试医院-" + tenantId);
        insertAccount(patientId, patientUsername, "PATIENT", null);
        insertAccount(doctorId, "payment_doctor_" + doctorId.toString().replace("-", ""),
                "DOCTOR", tenantId);
        jdbcTemplate.update("""
                insert into department
                    (id, tenant_id, name, description, status, created_at, updated_at)
                values (?, ?, ?, '', 'ACTIVE', current_timestamp, current_timestamp)
                """, departmentId.toString(), tenantId.toString(), "支付测试科-" + departmentId);
        jdbcTemplate.update("""
                insert into doctor_profile
                    (user_id, tenant_id, department_id, display_name, title, biography)
                values (?, ?, ?, '支付测试医生', '医师', '')
                """, doctorId.toString(), tenantId.toString(), departmentId.toString());
        jdbcTemplate.update("""
                insert into schedule_slot
                    (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                     capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '09:00', '10:00', 1, 39.00, 'PUBLISHED', 0,
                        current_timestamp, current_timestamp)
                """, slotId.toString(), tenantId.toString(), doctorId.toString(),
                departmentId.toString(), LocalDate.now().plusDays(7));
        jdbcTemplate.update("""
                insert into appointment
                    (id, tenant_id, patient_id, doctor_id, department_id, slot_id,
                     appointment_date, start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '09:00', '10:00', 39.00, 'COMPLETED',
                        current_timestamp, current_timestamp)
                """, appointmentId.toString(), tenantId.toString(), patientId.toString(),
                doctorId.toString(), departmentId.toString(), slotId.toString(), LocalDate.now().plusDays(7));
        jdbcTemplate.update("""
                insert into consultation
                    (id, appointment_id, tenant_id, patient_id, doctor_id, complaint, diagnosis,
                     advice, status, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, '持续头痛', '偏头痛', '规律休息', 'COMPLETED',
                        current_timestamp, current_timestamp, current_timestamp)
                """, consultationId.toString(), appointmentId.toString(), tenantId.toString(),
                patientId.toString(), doctorId.toString());
        jdbcTemplate.update("""
                insert into medical_order
                    (id, consultation_id, appointment_id, tenant_id, patient_id, amount, status,
                     transaction_no, paid_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'PENDING', null, null, current_timestamp, current_timestamp)
                """, orderId.toString(), consultationId.toString(), appointmentId.toString(),
                tenantId.toString(), patientId.toString(), new BigDecimal("39.00"));
    }

    private OtherOrder insertOtherPatientOrder() {
        UUID otherPatientId = UUID.randomUUID();
        String otherPatientUsername = "payment_other_" + otherPatientId.toString().replace("-", "");
        UUID otherSlotId = UUID.randomUUID();
        UUID otherAppointmentId = UUID.randomUUID();
        UUID otherConsultationId = UUID.randomUUID();
        UUID otherOrderId = UUID.randomUUID();
        insertAccount(otherPatientId, otherPatientUsername, "PATIENT", null);
        jdbcTemplate.update("""
                insert into schedule_slot
                    (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                     capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '10:00', '11:00', 1, 49.00, 'PUBLISHED', 0,
                        current_timestamp, current_timestamp)
                """, otherSlotId.toString(), tenantId.toString(), doctorId.toString(),
                departmentId.toString(), LocalDate.now().plusDays(8));
        jdbcTemplate.update("""
                insert into appointment
                    (id, tenant_id, patient_id, doctor_id, department_id, slot_id,
                     appointment_date, start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '10:00', '11:00', 49.00, 'COMPLETED',
                        current_timestamp, current_timestamp)
                """, otherAppointmentId.toString(), tenantId.toString(), otherPatientId.toString(),
                doctorId.toString(), departmentId.toString(), otherSlotId.toString(),
                LocalDate.now().plusDays(8));
        jdbcTemplate.update("""
                insert into consultation
                    (id, appointment_id, tenant_id, patient_id, doctor_id, complaint, diagnosis,
                     advice, status, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, '咳嗽', '上呼吸道感染', '多饮水', 'COMPLETED',
                        current_timestamp, current_timestamp, current_timestamp)
                """, otherConsultationId.toString(), otherAppointmentId.toString(), tenantId.toString(),
                otherPatientId.toString(), doctorId.toString());
        jdbcTemplate.update("""
                insert into medical_order
                    (id, consultation_id, appointment_id, tenant_id, patient_id, amount, status,
                     transaction_no, paid_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, 49.00, 'PENDING', null, null,
                        current_timestamp, current_timestamp)
                """, otherOrderId.toString(), otherConsultationId.toString(),
                otherAppointmentId.toString(), tenantId.toString(), otherPatientId.toString());
        return new OtherOrder(otherPatientId, otherPatientUsername, otherOrderId);
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }

    private void insertAccount(UUID id, String username, String role, UUID accountTenantId) {
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, current_timestamp, current_timestamp)
                """, id.toString(), username, DEMO_HASH, role,
                accountTenantId == null ? null : accountTenantId.toString());
    }

    private record OtherOrder(UUID patientId, String patientUsername, UUID orderId) {
    }
}
