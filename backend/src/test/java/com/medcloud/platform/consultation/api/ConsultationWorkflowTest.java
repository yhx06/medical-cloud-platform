package com.medcloud.platform.consultation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.consultation.application.ConsultationCompletedEvent;
import com.medcloud.platform.consultation.application.ConsultationService;
import com.medcloud.platform.consultation.application.ConsultationService.CorrectionCommand;
import com.medcloud.platform.consultation.application.ConsultationService.DraftCommand;
import com.medcloud.platform.consultation.application.ConsultationService.MedicalRecordResponse;
import com.medcloud.platform.consultation.application.ConsultationService.PrescriptionItemCommand;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConsultationWorkflowTest.EventTestConfiguration.class)
class ConsultationWorkflowTest {
    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
    private static final String DOCTOR_A = "20000000-0000-0000-0000-000000000001";
    private static final String PATIENT_ONE = "10000000-0000-0000-0000-000000000001";
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final ConsultationService service;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final EventProbe eventProbe;

    private String departmentId;

    @Autowired
    ConsultationWorkflowTest(
            ConsultationService service,
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            EventProbe eventProbe
    ) {
        this.service = service;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.eventProbe = eventProbe;
    }

    @BeforeEach
    void resetDatabase() {
        cleanBusinessData();
        eventProbe.reset();
        departmentId = null;
    }

    @AfterEach
    void removeBusinessData() {
        cleanBusinessData();
    }

    @Test
    void assignedDoctorCompletesRecordAndPublishesTheAppointmentSnapshotInsideTheTransaction() {
        String appointmentId = insertAppointment("39.00");
        CurrentUser doctor = doctorA();

        service.start(doctor, UUID.fromString(appointmentId));
        service.saveDraft(doctor, UUID.fromString(appointmentId), validDraft());
        MedicalRecordResponse completed = service.complete(doctor, UUID.fromString(appointmentId));

        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        assertThat(completed.complaint()).isEqualTo("反复头痛两天");
        assertThat(completed.prescriptionItems())
                .extracting(ConsultationService.PrescriptionItemResponse::medicationName)
                .containsExactly("布洛芬");
        assertThat(jdbcTemplate.queryForObject(
                "select status from appointment where id = ?", String.class, appointmentId))
                .isEqualTo("COMPLETED");

        assertThat(eventProbe.events()).hasSize(1);
        CapturedEvent captured = eventProbe.events().getFirst();
        assertThat(captured.transactionActive()).isTrue();
        assertThat(captured.event().consultationId()).isEqualTo(completed.id());
        assertThat(captured.event().appointmentId()).isEqualTo(UUID.fromString(appointmentId));
        assertThat(captured.event().patientId()).isEqualTo(UUID.fromString(PATIENT_ONE));
        assertThat(captured.event().tenantId()).isEqualTo(UUID.fromString(TENANT_A));
        assertThat(captured.event().fee()).isEqualByComparingTo("39.00");
    }

    @Test
    void anotherDoctorOrAForgedTenantCannotStartTheAssignedAppointment() {
        String appointmentId = insertAppointment("49.00");
        CurrentUser otherTenantDoctor = new CurrentUser(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "doctor_b",
                UserRole.DOCTOR,
                UUID.fromString(TENANT_B));
        CurrentUser assignedDoctorWithForgedTenant = new CurrentUser(
                UUID.fromString(DOCTOR_A),
                "doctor_a",
                UserRole.DOCTOR,
                UUID.fromString(TENANT_B));

        assertForbidden(() -> service.start(otherTenantDoctor, UUID.fromString(appointmentId)));
        assertForbidden(() -> service.start(assignedDoctorWithForgedTenant, UUID.fromString(appointmentId)));

        assertThat(jdbcTemplate.queryForObject(
                "select status from appointment where id = ?", String.class, appointmentId))
                .isEqualTo("BOOKED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from consultation", Integer.class)).isZero();
    }

    @Test
    void incompleteDraftCannotCompleteOrPublishAnEvent() {
        String appointmentId = insertAppointment("59.00");
        CurrentUser doctor = doctorA();
        service.start(doctor, UUID.fromString(appointmentId));
        service.saveDraft(doctor, UUID.fromString(appointmentId),
                new DraftCommand("头痛", " ", "观察", List.of()));

        assertThatThrownBy(() -> service.complete(doctor, UUID.fromString(appointmentId)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThat(jdbcTemplate.queryForObject(
                "select status from appointment where id = ?", String.class, appointmentId))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbcTemplate.queryForObject(
                "select status from consultation where appointment_id = ?", String.class, appointmentId))
                .isEqualTo("IN_PROGRESS");
        assertThat(eventProbe.events()).isEmpty();
    }

    @Test
    void anotherDraftReplacesThePersistedPrescriptionButCompletionBlocksFurtherOverwrites() {
        String appointmentId = insertAppointment("64.00");
        CurrentUser doctor = doctorA();
        UUID appointmentUuid = UUID.fromString(appointmentId);
        service.start(doctor, appointmentUuid);
        service.saveDraft(doctor, appointmentUuid, validDraft());

        DraftCommand replacement = new DraftCommand(
                "持续头痛并伴恶心",
                "偏头痛",
                "三日后复诊",
                List.of(new PrescriptionItemCommand("对乙酰氨基酚", "0.5g", "每日一次", 2)));
        MedicalRecordResponse secondDraft = service.saveDraft(doctor, appointmentUuid, replacement);

        assertThat(secondDraft.complaint()).isEqualTo("持续头痛并伴恶心");
        assertThat(secondDraft.prescriptionItems())
                .extracting(ConsultationService.PrescriptionItemResponse::medicationName)
                .containsExactly("对乙酰氨基酚");
        service.complete(doctor, appointmentUuid);

        assertThatThrownBy(() -> service.saveDraft(doctor, appointmentUuid, validDraft()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.MEDICAL_RECORD_COMPLETED));
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "select complaint, diagnosis, advice from consultation where appointment_id = ?",
                appointmentId);
        assertThat(stored).containsEntry("complaint", "持续头痛并伴恶心");
        assertThat(jdbcTemplate.queryForList(
                "select medication_name from prescription_item order by sequence_number",
                String.class)).containsExactly("对乙酰氨基酚");
    }

    @Test
    void aSynchronousListenerFailureRollsBackBothCompletionStateChanges() {
        String appointmentId = insertAppointment("69.00");
        CurrentUser doctor = doctorA();
        service.start(doctor, UUID.fromString(appointmentId));
        service.saveDraft(doctor, UUID.fromString(appointmentId), validDraft());
        eventProbe.failNextEvent();

        assertThatThrownBy(() -> service.complete(doctor, UUID.fromString(appointmentId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated order listener failure");

        entityManager.clear();
        assertThat(jdbcTemplate.queryForObject(
                "select status from appointment where id = ?", String.class, appointmentId))
                .isEqualTo("IN_PROGRESS");
        Map<String, Object> consultation = jdbcTemplate.queryForMap(
                "select status, completed_at from consultation where appointment_id = ?", appointmentId);
        assertThat(consultation.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(consultation.get("completed_at")).isNull();
    }

    @Test
    void concurrentStartsAreSerializedByTheDatabaseAndCreateOneConsultation() throws Exception {
        String appointmentId = insertAppointment("79.00");
        CurrentUser doctor = doctorA();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ErrorCode> start = () -> {
                try {
                    service.start(doctor, UUID.fromString(appointmentId));
                    return null;
                } catch (BusinessException exception) {
                    return exception.getCode();
                }
            };
            List<Future<ErrorCode>> futures = executor.invokeAll(List.of(start, start));
            List<ErrorCode> outcomes = Arrays.asList(futures.get(0).get(), futures.get(1).get());

            assertThat(outcomes).containsExactlyInAnyOrder(null, ErrorCode.APPOINTMENT_STATE_INVALID);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from consultation where appointment_id = ?",
                    Integer.class,
                    appointmentId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select status from appointment where id = ?", String.class, appointmentId))
                    .isEqualTo("IN_PROGRESS");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void patientEndpointsExposeOnlyTheAuthenticatedPatientsCompletedRecords() throws Exception {
        String appointmentId = insertAppointment("89.00");
        String doctorToken = bearer("doctor_a");
        String patientToken = bearer("patient_demo");
        String otherPatientToken = bearer(insertPatient());

        String started = mockMvc.perform(post("/api/doctor/appointments/{id}/start", appointmentId)
                        .header(AUTHORIZATION, doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.patientId").value(PATIENT_ONE))
                .andReturn().getResponse().getContentAsString();
        String recordId = objectMapper.readTree(started).at("/data/id").asText();

        mockMvc.perform(get("/api/patient/records").header(AUTHORIZATION, patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        Map<String, Object> maliciousDraft = Map.of(
                "complaint", "反复头痛两天",
                "diagnosis", "偏头痛待观察",
                "advice", "规律休息并复诊",
                "prescriptionItems", List.of(Map.of(
                        "medicationName", "布洛芬",
                        "dosage", "0.2g",
                        "frequency", "每日两次",
                        "durationDays", 3)),
                "tenantId", TENANT_B,
                "patientId", UUID.randomUUID().toString(),
                "doctorId", UUID.randomUUID().toString());
        mockMvc.perform(put("/api/doctor/consultations/{appointmentId}/draft", appointmentId)
                        .header(AUTHORIZATION, doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousDraft)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.data.patientId").value(PATIENT_ONE))
                .andExpect(jsonPath("$.data.doctorId").value(DOCTOR_A));
        mockMvc.perform(post("/api/doctor/consultations/{appointmentId}/complete", appointmentId)
                        .header(AUTHORIZATION, doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(recordId));

        mockMvc.perform(get("/api/patient/records").header(AUTHORIZATION, patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(recordId))
                .andExpect(jsonPath("$.data[0].patientId").value(PATIENT_ONE));
        mockMvc.perform(get("/api/patient/records/{id}", recordId).header(AUTHORIZATION, patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prescriptionItems[0].medicationName").value("布洛芬"));
        mockMvc.perform(get("/api/patient/records/{id}", recordId).header(AUTHORIZATION, otherPatientToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        for (String admin : List.of("hospital_a", "platform_admin")) {
            mockMvc.perform(get("/api/patient/records/{id}", recordId)
                            .header(AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        }
    }

    @Test
    void correctionsAppendInOrderWithoutChangingTheCompletedRecordOrPrescription() throws Exception {
        String appointmentId = insertAppointment("99.00");
        CurrentUser doctor = doctorA();
        MedicalRecordResponse started = service.start(doctor, UUID.fromString(appointmentId));
        service.saveDraft(doctor, UUID.fromString(appointmentId), validDraft());
        service.complete(doctor, UUID.fromString(appointmentId));

        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Void>> corrections = List.of(
                    () -> {
                        service.appendCorrection(doctor, started.id(), new CorrectionCommand("补充青霉素过敏史"));
                        return null;
                    },
                    () -> {
                        service.appendCorrection(doctor, started.id(), new CorrectionCommand("复诊后补充血压正常"));
                        return null;
                    });
            for (Future<Void> future : executor.invokeAll(corrections)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        MedicalRecordResponse record = service.patientRecord(patientOne(), started.id());
        assertThat(record.complaint()).isEqualTo("反复头痛两天");
        assertThat(record.diagnosis()).isEqualTo("偏头痛待观察");
        assertThat(record.advice()).isEqualTo("规律休息并复诊");
        assertThat(record.prescriptionItems())
                .extracting(ConsultationService.PrescriptionItemResponse::medicationName)
                .containsExactly("布洛芬");
        assertThat(record.corrections())
                .extracting(ConsultationService.RecordCorrectionResponse::sequenceNumber)
                .containsExactly(1, 2);
        assertThat(record.corrections())
                .extracting(ConsultationService.RecordCorrectionResponse::reason)
                .containsExactlyInAnyOrder("补充青霉素过敏史", "复诊后补充血压正常");
        assertThat(record.corrections())
                .extracting(ConsultationService.RecordCorrectionResponse::doctorId)
                .containsOnly(UUID.fromString(DOCTOR_A));

        assertForbidden(() -> service.appendCorrection(
                new CurrentUser(
                        UUID.fromString("20000000-0000-0000-0000-000000000002"),
                        "doctor_b",
                        UserRole.DOCTOR,
                        UUID.fromString(TENANT_B)),
                started.id(),
                new CorrectionCommand("越权更正")));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from record_correction where consultation_id = ?",
                Integer.class,
                started.id().toString())).isEqualTo(2);
    }

    private DraftCommand validDraft() {
        return new DraftCommand(
                "反复头痛两天",
                "偏头痛待观察",
                "规律休息并复诊",
                List.of(new PrescriptionItemCommand("布洛芬", "0.2g", "每日两次", 3)));
    }

    private CurrentUser doctorA() {
        return new CurrentUser(
                UUID.fromString(DOCTOR_A),
                "doctor_a",
                UserRole.DOCTOR,
                UUID.fromString(TENANT_A));
    }

    private CurrentUser patientOne() {
        return new CurrentUser(
                UUID.fromString(PATIENT_ONE),
                "patient_demo",
                UserRole.PATIENT,
                null);
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    private String insertAppointment(String fee) {
        if (departmentId == null) {
            departmentId = UUID.randomUUID().toString();
            jdbcTemplate.update("""
                    insert into department
                        (id, tenant_id, name, description, status, created_at, updated_at)
                    values (?, ?, ?, '', 'ACTIVE', current_timestamp, current_timestamp)
                    """, departmentId, TENANT_A, "接诊测试科-" + departmentId);
            jdbcTemplate.update("""
                    insert into doctor_profile
                        (user_id, tenant_id, department_id, display_name, title, biography)
                    values (?, ?, ?, '接诊测试医生', '医师', '')
                    """, DOCTOR_A, TENANT_A, departmentId);
        }
        String slotId = UUID.randomUUID().toString();
        String appointmentId = UUID.randomUUID().toString();
        LocalDate date = LocalDate.now().plusDays(7);
        jdbcTemplate.update("""
                insert into schedule_slot
                    (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                     capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, '09:00', '10:00', 1, ?, 'PUBLISHED', 0,
                        current_timestamp, current_timestamp)
                """, slotId, TENANT_A, DOCTOR_A, departmentId, date, new BigDecimal(fee));
        jdbcTemplate.update("""
                insert into appointment
                    (id, tenant_id, patient_id, doctor_id, department_id, slot_id,
                     appointment_date, start_time, end_time, fee, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, '09:00', '10:00', ?, 'BOOKED',
                        current_timestamp, current_timestamp)
                """, appointmentId, TENANT_A, PATIENT_ONE, DOCTOR_A, departmentId, slotId,
                date, new BigDecimal(fee));
        return appointmentId;
    }

    private String insertPatient() {
        String username = "consultation_patient_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, 'PATIENT', 'ACTIVE', null, current_timestamp, current_timestamp)
                """, UUID.randomUUID().toString(), username, DEMO_HASH);
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

    private void cleanBusinessData() {
        for (String table : List.of(
                "record_correction", "prescription_item", "consultation", "appointment",
                "schedule_slot", "doctor_profile", "department")) {
            jdbcTemplate.update("delete from " + table);
        }
        jdbcTemplate.update("delete from user_account where username like 'consultation_patient_%'");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EventTestConfiguration {
        @Bean
        EventProbe eventProbe() {
            return new EventProbe();
        }
    }

    static final class EventProbe {
        private final List<CapturedEvent> events = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean failNext = new AtomicBoolean();

        @EventListener
        public void onApplicationEvent(ConsultationCompletedEvent event) {
            events.add(new CapturedEvent(
                    event,
                    TransactionSynchronizationManager.isActualTransactionActive()));
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated order listener failure");
            }
        }

        List<CapturedEvent> events() {
            return List.copyOf(events);
        }

        void failNextEvent() {
            failNext.set(true);
        }

        void reset() {
            events.clear();
            failNext.set(false);
        }
    }

    record CapturedEvent(ConsultationCompletedEvent event, boolean transactionActive) {
    }
}
