package com.medcloud.platform.order.application;

import com.medcloud.platform.consultation.application.ConsultationCompletedEvent;
import com.medcloud.platform.consultation.application.ConsultationService;
import com.medcloud.platform.consultation.application.ConsultationService.DraftCommand;
import com.medcloud.platform.consultation.application.ConsultationService.PrescriptionItemCommand;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.order.application.MedicalOrderService.MedicalOrderResponse;
import com.medcloud.platform.order.domain.OrderStatus;
import com.medcloud.platform.security.CurrentUser;
import org.h2.api.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderCreationTest {
    private static final String ORDER_INSERT_FAILURE_TRIGGER = "medical_order_insert_failure";
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final MedicalOrderService orderService;
    private final ConsultationService consultationService;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    private UUID tenantId;
    private UUID patientId;
    private UUID doctorId;
    private UUID departmentId;
    private UUID slotId;
    private UUID appointmentId;
    private UUID consultationId;
    private boolean h2;

    @Autowired
    OrderCreationTest(
            MedicalOrderService orderService,
            ConsultationService consultationService,
            TransactionTemplate transactions,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource
    ) {
        this.orderService = orderService;
        this.consultationService = consultationService;
        this.transactions = transactions;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @BeforeEach
    void createCompletedConsultationFixture() throws SQLException {
        h2 = databaseProductName().contains("H2");
        tenantId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        doctorId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        slotId = UUID.randomUUID();
        appointmentId = UUID.randomUUID();
        consultationId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> insertFixture("COMPLETED", "COMPLETED"));
    }

    @AfterEach
    void removeFixture() {
        jdbcTemplate.execute("drop trigger if exists " + ORDER_INSERT_FAILURE_TRIGGER);
        if (tenantId != null) {
            jdbcTemplate.update("delete from medical_order where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("""
                    delete from record_correction where consultation_id in
                        (select id from consultation where tenant_id = ?)
                    """, tenantId.toString());
            jdbcTemplate.update("""
                    delete from prescription_item where consultation_id in
                        (select id from consultation where tenant_id = ?)
                    """, tenantId.toString());
            jdbcTemplate.update("delete from consultation where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from appointment where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from schedule_slot where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from doctor_profile where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from user_account where id in (?, ?)",
                    doctorId.toString(), patientId.toString());
            jdbcTemplate.update("delete from department where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from tenant where id = ?", tenantId.toString());
        }
    }

    @Test
    void completedConsultationCreatesPendingOrderFromTheServerEventSnapshot() {
        ConsultationCompletedEvent event = completedEvent("39.00");

        MedicalOrderResponse order = orderService.createForCompletedConsultation(event);

        assertThat(order.consultationId()).isEqualTo(consultationId);
        assertThat(order.appointmentId()).isEqualTo(appointmentId);
        assertThat(order.amount()).isEqualByComparingTo("39.00");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.transactionNo()).isNull();
        assertThat(order.paidAt()).isNull();
        assertThat(jdbcTemplate.queryForMap(
                "select patient_id, tenant_id, amount from medical_order where id = ?",
                order.id().toString()))
                .containsEntry("patient_id", patientId.toString())
                .containsEntry("tenant_id", tenantId.toString());
        assertThat((BigDecimal) jdbcTemplate.queryForMap(
                "select amount from medical_order where id = ?", order.id().toString()).get("amount"))
                .isEqualByComparingTo("39.00");
    }

    @Test
    void repeatedCompletedEventReturnsTheExistingOrder() {
        ConsultationCompletedEvent event = completedEvent("49.00");

        MedicalOrderResponse first = orderService.createForCompletedConsultation(event);
        MedicalOrderResponse second = orderService.createForCompletedConsultation(event);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from medical_order where consultation_id = ?",
                Integer.class,
                consultationId.toString())).isEqualTo(1);
    }

    @Test
    void outerRollbackRemovesOrderCreatedAuditTogetherWithTheOrder() {
        ConsultationCompletedEvent event = completedEvent("59.00");
        AtomicReference<UUID> orderId = new AtomicReference<>();

        transactions.executeWithoutResult(status -> {
            orderId.set(orderService.createForCompletedConsultation(event).id());
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from medical_order where consultation_id = ?",
                Integer.class, consultationId.toString())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_event
                where action = 'ORDER_CREATED' and object_id = ?
                """, Integer.class, orderId.get().toString())).isZero();
    }

    @Test
    void concurrentRepeatedEventsUseIndependentTransactionsAndCreateOneOrder() throws Exception {
        ConsultationCompletedEvent event = completedEvent("59.00");
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MedicalOrderResponse> first = executor.submit(() -> create(startTogether, event));
            Future<MedicalOrderResponse> second = executor.submit(() -> create(startTogether, event));

            MedicalOrderResponse firstOrder = first.get(10, TimeUnit.SECONDS);
            MedicalOrderResponse secondOrder = second.get(10, TimeUnit.SECONDS);
            assertThat(secondOrder.id()).isEqualTo(firstOrder.id());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from medical_order where consultation_id = ?",
                    Integer.class,
                    consultationId.toString())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void orderInsertFailureRollsBackConsultationAndAppointmentCompletion() {
        jdbcTemplate.update("delete from consultation where id = ?", consultationId.toString());
        jdbcTemplate.update("update appointment set status = 'BOOKED' where id = ?", appointmentId.toString());
        CurrentUser doctor = new CurrentUser(doctorId, "order-doctor", UserRole.DOCTOR, tenantId);
        consultationService.start(doctor, appointmentId);
        consultationService.saveDraft(doctor, appointmentId, new DraftCommand(
                "持续头痛",
                "偏头痛",
                "规律休息",
                List.of(new PrescriptionItemCommand("布洛芬", "0.2g", "每日两次", 3))));
        if (h2) {
            jdbcTemplate.execute("create trigger " + ORDER_INSERT_FAILURE_TRIGGER
                    + " before insert on medical_order for each row call '"
                    + FailingOrderInsertTrigger.class.getName() + "'");
        } else {
            jdbcTemplate.execute("create trigger " + ORDER_INSERT_FAILURE_TRIGGER
                    + " before insert on medical_order for each row "
                    + "signal sqlstate '45000' set message_text = 'simulated medical order insert failure'");
        }

        assertThatThrownBy(() -> consultationService.complete(doctor, appointmentId))
                .isInstanceOf(RuntimeException.class);

        Map<String, Object> appointment = jdbcTemplate.queryForMap(
                "select status from appointment where id = ?", appointmentId.toString());
        Map<String, Object> consultation = jdbcTemplate.queryForMap(
                "select status, completed_at from consultation where appointment_id = ?",
                appointmentId.toString());
        assertThat(appointment.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(consultation.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(consultation.get("completed_at")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from medical_order where appointment_id = ?",
                Integer.class,
                appointmentId.toString())).isZero();
    }

    private MedicalOrderResponse create(
            CyclicBarrier startTogether,
            ConsultationCompletedEvent event
    ) throws Exception {
        startTogether.await(5, TimeUnit.SECONDS);
        return orderService.createForCompletedConsultation(event);
    }

    private ConsultationCompletedEvent completedEvent(String fee) {
        return new ConsultationCompletedEvent(
                consultationId,
                appointmentId,
                patientId,
                tenantId,
                new BigDecimal(fee));
    }

    private void insertFixture(String appointmentStatus, String consultationStatus) {
        jdbcTemplate.update("""
                insert into tenant (id, name, status, created_at, updated_at)
                values (?, ?, 'ACTIVE', current_timestamp, current_timestamp)
                """, tenantId.toString(), "订单测试医院-" + tenantId);
        insertAccount(patientId, "order_patient_", "PATIENT", null);
        insertAccount(doctorId, "order_doctor_", "DOCTOR", tenantId);
        jdbcTemplate.update("""
                insert into department
                    (id, tenant_id, name, description, status, created_at, updated_at)
                values (?, ?, ?, '', 'ACTIVE', current_timestamp, current_timestamp)
                """, departmentId.toString(), tenantId.toString(), "订单测试科-" + departmentId);
        jdbcTemplate.update("""
                insert into doctor_profile
                    (user_id, tenant_id, department_id, display_name, title, biography)
                values (?, ?, ?, '订单测试医生', '医师', '')
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
                values (?, ?, ?, ?, ?, ?, ?, '09:00', '10:00', 39.00, ?,
                        current_timestamp, current_timestamp)
                """, appointmentId.toString(), tenantId.toString(), patientId.toString(),
                doctorId.toString(), departmentId.toString(), slotId.toString(),
                LocalDate.now().plusDays(7), appointmentStatus);
        jdbcTemplate.update("""
                insert into consultation
                    (id, appointment_id, tenant_id, patient_id, doctor_id, complaint, diagnosis,
                     advice, status, completed_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, '持续头痛', '偏头痛', '规律休息', ?, current_timestamp,
                        current_timestamp, current_timestamp)
                """, consultationId.toString(), appointmentId.toString(), tenantId.toString(),
                patientId.toString(), doctorId.toString(), consultationStatus);
    }

    private void insertAccount(UUID id, String usernamePrefix, String role, UUID accountTenantId) {
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, current_timestamp, current_timestamp)
                """, id.toString(), usernamePrefix + id.toString().replace("-", ""), DEMO_HASH, role,
                accountTenantId == null ? null : accountTenantId.toString());
    }

    private String databaseProductName() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        }
    }

    public static final class FailingOrderInsertTrigger implements Trigger {
        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            throw new SQLException("simulated medical order insert failure");
        }
    }
}
