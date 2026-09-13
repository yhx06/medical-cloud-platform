package com.medcloud.platform.schedule.application;

import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.identity.infrastructure.UserAccountRepository;
import com.medcloud.platform.schedule.application.ScheduleService.PublishSlotRequest;
import com.medcloud.platform.schedule.application.ScheduleService.ScheduleSlotResponse;
import com.medcloud.platform.security.CurrentUser;
import com.medcloud.platform.tenant.domain.Department;
import com.medcloud.platform.tenant.domain.DoctorProfile;
import com.medcloud.platform.tenant.domain.Tenant;
import com.medcloud.platform.tenant.infrastructure.DepartmentRepository;
import com.medcloud.platform.tenant.infrastructure.DoctorProfileRepository;
import com.medcloud.platform.tenant.infrastructure.TenantRepository;
import org.h2.api.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
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
class SchedulePublicationConcurrencyTest {
    private static final String TRIGGER_NAME = "schedule_insert_barrier";

    private final ScheduleService scheduleService;
    private final TenantRepository tenants;
    private final DepartmentRepository departments;
    private final DoctorProfileRepository doctorProfiles;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    private UUID tenantId;
    private UUID departmentId;
    private UUID doctorId;
    private boolean h2;

    @Autowired
    SchedulePublicationConcurrencyTest(
            ScheduleService scheduleService,
            TenantRepository tenants,
            DepartmentRepository departments,
            DoctorProfileRepository doctorProfiles,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactions,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource
    ) {
        this.scheduleService = scheduleService;
        this.tenants = tenants;
        this.departments = departments;
        this.doctorProfiles = doctorProfiles;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @BeforeEach
    void createCommittedScheduleFixture() throws SQLException {
        h2 = databaseProductName().contains("H2");
        transactions.executeWithoutResult(status -> {
            Tenant tenant = tenants.save(Tenant.create("并发排班医院-" + UUID.randomUUID()));
            Department department = departments.save(Department.create(tenant.getId(), "并发排班科", ""));
            UserAccount doctor = users.save(UserAccount.createDoctor(
                    tenant.getId(), "concurrent_doctor_" + UUID.randomUUID(), passwordEncoder.encode("Doctor@123456")));
            doctorProfiles.save(DoctorProfile.create(
                    doctor.getId(), tenant.getId(), department.getId(), "并发医生", "医师", ""));
            tenantId = tenant.getId();
            departmentId = department.getId();
            doctorId = doctor.getId();
        });
    }

    @AfterEach
    void removeCommittedScheduleFixture() {
        if (h2) {
            jdbcTemplate.execute("drop trigger if exists " + TRIGGER_NAME);
        }
        if (tenantId != null) {
            jdbcTemplate.update("delete from schedule_slot where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from doctor_profile where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from user_account where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from department where tenant_id = ?", tenantId.toString());
            jdbcTemplate.update("delete from tenant where id = ?", tenantId.toString());
        }
    }

    @Test
    void concurrentOverlappingPublicationsCommitExactlyOneSlot() throws Exception {
        if (h2) {
            ConcurrentInsertBarrier.reset();
            jdbcTemplate.execute("create trigger " + TRIGGER_NAME
                    + " before insert on schedule_slot for each row call '"
                    + ConcurrentInsertBarrier.class.getName() + "'");
        }
        CurrentUser hospital = new CurrentUser(
                UUID.randomUUID(), "concurrent_hospital", UserRole.HOSPITAL_ADMIN, tenantId);
        PublishSlotRequest request = new PublishSlotRequest(
                doctorId,
                LocalDate.now().plusDays(30),
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                2,
                new BigDecimal("39.00"));

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PublishAttempt> first = executor.submit(() -> publish(startTogether, hospital, request));
            Future<PublishAttempt> second = executor.submit(() -> publish(startTogether, hospital, request));
            List<PublishAttempt> attempts = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(attempts).filteredOn(PublishAttempt::succeeded).hasSize(1);
            assertThat(attempts).filteredOn(attempt -> attempt.errorCode() == ErrorCode.SCHEDULE_OVERLAP)
                    .hasSize(1);
            Integer committed = jdbcTemplate.queryForObject(
                    "select count(*) from schedule_slot where doctor_id = ?", Integer.class, doctorId.toString());
            assertThat(committed).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private PublishAttempt publish(
            CyclicBarrier startTogether,
            CurrentUser hospital,
            PublishSlotRequest request
    ) throws Exception {
        startTogether.await(5, TimeUnit.SECONDS);
        try {
            ScheduleSlotResponse response = scheduleService.publish(hospital, request);
            return new PublishAttempt(response.id(), null);
        } catch (BusinessException exception) {
            return new PublishAttempt(null, exception.getCode());
        }
    }

    private String databaseProductName() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        }
    }

    private record PublishAttempt(UUID slotId, ErrorCode errorCode) {
        boolean succeeded() {
            return slotId != null;
        }
    }

    public static final class ConcurrentInsertBarrier implements Trigger {
        private static volatile CountDownLatch arrivals = new CountDownLatch(2);

        public static void reset() {
            arrivals = new CountDownLatch(2);
        }

        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) {
            CountDownLatch current = arrivals;
            current.countDown();
            try {
                current.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
