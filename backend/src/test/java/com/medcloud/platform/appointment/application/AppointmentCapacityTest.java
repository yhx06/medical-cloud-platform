package com.medcloud.platform.appointment.application;

import com.medcloud.platform.appointment.domain.AppointmentStatus;
import com.medcloud.platform.appointment.infrastructure.AppointmentRepository;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserRole;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AppointmentCapacityTest {
    private static final String TRIGGER_NAME = "appointment_insert_barrier";
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final AppointmentService appointmentService;
    private final AppointmentRepository appointments;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    private UUID tenantId;
    private UUID departmentId;
    private UUID doctorId;
    private UUID slotId;
    private UUID firstPatientId;
    private UUID secondPatientId;
    private boolean h2;

    @Autowired
    AppointmentCapacityTest(
            AppointmentService appointmentService,
            AppointmentRepository appointments,
            TransactionTemplate transactions,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource
    ) {
        this.appointmentService = appointmentService;
        this.appointments = appointments;
        this.transactions = transactions;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @BeforeEach
    void createCommittedCapacityFixture() throws SQLException {
        h2 = databaseProductName().contains("H2");
        tenantId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        doctorId = UUID.randomUUID();
        slotId = UUID.randomUUID();
        firstPatientId = UUID.randomUUID();
        secondPatientId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    insert into tenant (id, name, status, created_at, updated_at)
                    values (?, '并发预约医院', 'ACTIVE', current_timestamp, current_timestamp)
                    """, tenantId.toString());
            jdbcTemplate.update("""
                    insert into department
                        (id, tenant_id, name, description, status, created_at, updated_at)
                    values (?, ?, '并发预约科', '', 'ACTIVE', current_timestamp, current_timestamp)
                    """, departmentId.toString(), tenantId.toString());
            insertAccount(doctorId, "concurrent_doctor_", "DOCTOR", tenantId);
            insertAccount(firstPatientId, "concurrent_patient_", "PATIENT", null);
            insertAccount(secondPatientId, "concurrent_patient_", "PATIENT", null);
            jdbcTemplate.update("""
                    insert into doctor_profile
                        (user_id, tenant_id, department_id, display_name, title, biography)
                    values (?, ?, ?, '并发预约医生', '医师', '')
                    """, doctorId.toString(), tenantId.toString(), departmentId.toString());
            jdbcTemplate.update("""
                    insert into schedule_slot
                        (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                         capacity, fee, status, version, created_at, updated_at)
                    values (?, ?, ?, ?, ?, '09:00', '10:00', 1, 39.00, 'PUBLISHED', 0,
                            current_timestamp, current_timestamp)
                    """, slotId.toString(), tenantId.toString(), doctorId.toString(),
                    departmentId.toString(), LocalDate.now().plusDays(20));
        });
    }

    @AfterEach
    void removeCommittedCapacityFixture() {
        if (h2) {
            jdbcTemplate.execute("drop trigger if exists " + TRIGGER_NAME);
        }
        if (tenantId != null) {
            jdbcTemplate.update("delete from appointment where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from schedule_slot where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from doctor_profile where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from user_account where id in (?, ?, ?)",
                    doctorId.toString(), firstPatientId.toString(), secondPatientId.toString());
            jdbcTemplate.update("delete from department where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from tenant where id = ?", tenantId.toString());
        }
    }

    @Test
    void concurrentBookingsUseIndependentTransactionsAndNeverExceedSlotCapacity() throws Exception {
        if (h2) {
            ConcurrentInsertBarrier.reset();
            jdbcTemplate.execute("create trigger " + TRIGGER_NAME
                    + " before insert on appointment for each row call '"
                    + ConcurrentInsertBarrier.class.getName() + "'");
        }
        CurrentUser first = patient(firstPatientId);
        CurrentUser second = patient(secondPatientId);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BookingAttempt> firstAttempt = executor.submit(() -> book(startTogether, first));
            Future<BookingAttempt> secondAttempt = executor.submit(() -> book(startTogether, second));
            List<BookingAttempt> attempts = List.of(
                    firstAttempt.get(10, TimeUnit.SECONDS),
                    secondAttempt.get(10, TimeUnit.SECONDS));

            assertThat(attempts).filteredOn(BookingAttempt::succeeded).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> attempt.errorCode() == ErrorCode.SCHEDULE_FULL)
                    .hasSize(1);
            assertThat(appointments.countBySlotIdAndStatusNot(slotId, AppointmentStatus.CANCELLED))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private BookingAttempt book(CyclicBarrier startTogether, CurrentUser patient) throws Exception {
        startTogether.await(5, TimeUnit.SECONDS);
        try {
            AppointmentService.AppointmentResponse response = appointmentService.book(patient, slotId);
            return new BookingAttempt(response.id(), null);
        } catch (BusinessException exception) {
            return new BookingAttempt(null, exception.getCode());
        }
    }

    private void insertAccount(UUID id, String usernamePrefix, String role, UUID accountTenantId) {
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, current_timestamp, current_timestamp)
                """, id.toString(), usernamePrefix + id.toString().replace("-", ""), DEMO_HASH, role,
                accountTenantId == null ? null : accountTenantId.toString());
    }

    private CurrentUser patient(UUID patientId) {
        return new CurrentUser(patientId, "patient-" + patientId, UserRole.PATIENT, null);
    }

    private String databaseProductName() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        }
    }

    private record BookingAttempt(UUID appointmentId, ErrorCode errorCode) {
        boolean succeeded() {
            return appointmentId != null;
        }
    }

    public static final class ConcurrentInsertBarrier implements Trigger {
        private static volatile CountDownLatch arrivals = new CountDownLatch(2);

        static void reset() {
            arrivals = new CountDownLatch(2);
        }

        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) {
            CountDownLatch current = arrivals;
            current.countDown();
            try {
                current.await(750, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
