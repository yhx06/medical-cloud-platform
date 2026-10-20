package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.SlotRequest;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.schedule.SlotAdminService;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.Tenant;
import com.medcloud.course.paas.tenant.HospitalDepartmentService;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Date;
import java.sql.Time;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HospitalSlotTest {
    @Test void departmentDisableCannotCommitAroundUnfinishedPublication() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch departmentRead = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CurrentActor admin = new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a");
        try {
            EntityManager setup = factory.createEntityManager();
            try {
                setup.getTransaction().begin();
                setup.persist(new Tenant("tenant-a", "甲医院"));
                setup.persist(new Department("dept-a", "tenant-a", "内科"));
                setup.persist(new Account("admin-a", "admin_a", "hash", Role.HOSPITAL_ADMIN, "tenant-a"));
                setup.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
                setup.persist(new DoctorProfile("doctor-a", "tenant-a", "dept-a", "甲医生"));
                setup.getTransaction().commit();
            } finally { setup.close(); }
            Future<Boolean> publishing = workers.submit(() -> {
                EntityManager manager = factory.createEntityManager();
                try {
                    manager.getTransaction().begin();
                    new SlotAdminService(pausingAfterDepartmentRead(manager, departmentRead, releasePublication))
                            .create(admin, new SlotRequest("dept-a", "doctor-a", Date.valueOf("2035-05-01"),
                                    Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 1, BigDecimal.TEN, null));
                    manager.getTransaction().commit();
                    return true;
                } finally {
                    if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
                    manager.close();
                }
            });
            assertTrue(departmentRead.await(10, TimeUnit.SECONDS));
            Future<Boolean> disabling = workers.submit(() -> {
                EntityManager manager = factory.createEntityManager();
                try {
                    manager.getTransaction().begin();
                    new HospitalDepartmentService(manager).update(admin, "dept-a", "内科", "DISABLED");
                    manager.getTransaction().commit();
                    return true;
                } catch (IllegalStateException rejected) {
                    if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
                    return false;
                } finally { manager.close(); }
            });
            try { disabling.get(500, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException waitingOnDepartmentLock) { }
            finally { releasePublication.countDown(); }
            assertTrue(publishing.get(10, TimeUnit.SECONDS));
            assertFalse(disabling.get(10, TimeUnit.SECONDS));
            EntityManager verify = factory.createEntityManager();
            try {
                assertEquals("ACTIVE", verify.find(Department.class, "dept-a").getStatus());
                assertEquals(1L, verify.createQuery("select count(s) from ScheduleSlot s where s.departmentId = 'dept-a'", Long.class)
                        .getSingleResult());
            } finally { verify.close(); }
        } finally {
            releasePublication.countDown(); workers.shutdownNow(); factory.close();
        }
    }

    private static EntityManager pausingAfterDepartmentRead(EntityManager delegate, CountDownLatch read,
                                                              CountDownLatch release) {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                    Object result;
                    try { result = method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                    if ("find".equals(method.getName()) && args != null && args.length >= 2
                            && args[0] == Department.class && "dept-a".equals(args[1])) {
                        read.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("publication was not released");
                    }
                    return result;
                });
    }

    @Test void publishesOnlyOwnActiveResourcesAndDerivesTenant() {
        Fixture f = new Fixture();
        try {
            f.begin();
            ScheduleSlot slot = f.service.create(f.admin, f.request(f.department, f.doctor, "PUBLISHED"));
            assertEquals(f.tenant, slot.getTenantId());
            assertEquals(1, f.service.list(f.admin, 1, 100).getItems().size());
            assertThrows(AccessDeniedException.class, () -> f.service.create(f.admin, f.request(f.foreignDepartment, f.foreignDoctor, "PUBLISHED")));
            assertThrows(AccessDeniedException.class, () -> f.service.update(f.admin, f.foreignSlot, f.request(f.department, f.doctor, "PUBLISHED")));
            f.manager.find(Account.class, f.doctor).setStatus("DISABLED");
            assertThrows(IllegalStateException.class, () -> f.service.create(f.admin, f.request(f.department, f.doctor, "PUBLISHED")));
            f.commit();
        } finally { f.close(); }
    }

    @Test void rejectsInvalidPastAndDuplicateSlots() {
        Fixture f = new Fixture();
        try {
            f.begin();
            assertThrows(IllegalArgumentException.class, () -> f.service.create(f.admin, new SlotRequest(f.department, f.doctor,
                    Date.valueOf("2035-05-01"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 0, BigDecimal.TEN, null)));
            assertThrows(IllegalArgumentException.class, () -> f.service.create(f.admin, new SlotRequest(f.department, f.doctor,
                    Date.valueOf("2020-05-01"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 2, BigDecimal.TEN, null)));
            f.service.create(f.admin, f.request(f.department, f.doctor, "PUBLISHED"));
            assertThrows(IllegalStateException.class, () -> f.service.create(f.admin, f.request(f.department, f.doctor, "PUBLISHED")));
            f.commit();
        } finally { f.close(); }
    }

    @Test void bookedSlotCannotChangeOrWithdrawButUnbookedSlotCan() {
        Fixture f = new Fixture();
        try {
            f.begin();
            ScheduleSlot slot = f.service.create(f.admin, f.request(f.department, f.doctor, "PUBLISHED"));
            f.manager.persist(new Appointment(UUID.randomUUID().toString(), f.patient, slot.getId(), f.tenant,
                    f.doctor, BigDecimal.TEN, Date.valueOf("2035-04-30")));
            assertEquals(1, f.service.remaining(slot));
            assertThrows(IllegalStateException.class, () -> f.service.update(f.admin, slot.getId(), f.request(f.department, f.doctor, "WITHDRAWN")));
            assertThrows(IllegalStateException.class, () -> f.service.update(f.admin, slot.getId(), new SlotRequest(f.department,
                    f.doctor, Date.valueOf("2035-05-01"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 1, BigDecimal.TEN, "PUBLISHED")));
            ScheduleSlot other = f.service.create(f.admin, new SlotRequest(f.department, f.doctor,
                    Date.valueOf("2035-05-02"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 2, BigDecimal.TEN, null));
            assertEquals("WITHDRAWN", f.service.update(f.admin, other.getId(), new SlotRequest(f.department, f.doctor,
                    Date.valueOf("2035-05-02"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 2, BigDecimal.TEN, "WITHDRAWN")).getStatus());
            f.commit();
        } finally { f.close(); }
    }

    private static final class Fixture {
        final EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        final EntityManager manager = factory.createEntityManager();
        final String tenant = UUID.randomUUID().toString(), foreignTenant = UUID.randomUUID().toString();
        final String department = UUID.randomUUID().toString(), foreignDepartment = UUID.randomUUID().toString();
        final String doctor = UUID.randomUUID().toString(), foreignDoctor = UUID.randomUUID().toString();
        final String patient = UUID.randomUUID().toString(), foreignSlot = UUID.randomUUID().toString();
        final CurrentActor admin;
        final SlotAdminService service = new SlotAdminService(manager);
        Fixture() {
            manager.getTransaction().begin();
            manager.persist(new Tenant(tenant, "甲医院")); manager.persist(new Tenant(foreignTenant, "乙医院"));
            manager.persist(new Department(department, tenant, "内科"));
            manager.persist(new Department(foreignDepartment, foreignTenant, "外科"));
            String adminId = UUID.randomUUID().toString();
            manager.persist(new Account(adminId, "admin", "hash", Role.HOSPITAL_ADMIN, tenant));
            manager.persist(new Account(doctor, "doctor", "hash", Role.DOCTOR, tenant));
            manager.persist(new Account(foreignDoctor, "foreign", "hash", Role.DOCTOR, foreignTenant));
            manager.persist(new Account(patient, "patient", "hash", Role.PATIENT, null));
            manager.persist(new DoctorProfile(doctor, tenant, department, "甲医生"));
            manager.persist(new DoctorProfile(foreignDoctor, foreignTenant, foreignDepartment, "乙医生"));
            manager.persist(new ScheduleSlot(foreignSlot, foreignTenant, foreignDepartment, foreignDoctor,
                    Date.valueOf("2035-05-01"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 2, BigDecimal.TEN));
            manager.getTransaction().commit();
            admin = new CurrentActor(adminId, Role.HOSPITAL_ADMIN, tenant);
        }
        SlotRequest request(String dept, String doc, String status) {
            return new SlotRequest(dept, doc, Date.valueOf("2035-05-01"), Time.valueOf("09:00:00"),
                    Time.valueOf("10:00:00"), 2, BigDecimal.TEN, status);
        }
        void begin() { manager.getTransaction().begin(); }
        void commit() { manager.getTransaction().commit(); }
        void close() { if (manager.getTransaction().isActive()) manager.getTransaction().rollback(); manager.close(); factory.close(); }
    }
}
