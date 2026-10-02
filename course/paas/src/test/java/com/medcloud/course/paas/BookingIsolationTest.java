package com.medcloud.course.paas;

import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.HospitalDoctorService;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.LockModeType;
import javax.persistence.Persistence;
import javax.persistence.TypedQuery;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingIsolationTest {
    private static final String SECRET = "course-test-signing-secret-at-least-32-characters";

    @Test
    void concurrentCancellationAndBookingPreserveLastPlaceCapacity() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        String firstId;
        EntityManager setup = factory.createEntityManager();
        try {
            setup.getTransaction().begin();
            setup.persist(new Tenant("tenant-a", "仁和云诊所"));
            setup.persist(new Department("department-a", "tenant-a", "全科"));
            setup.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            setup.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "Doctor"));
            setup.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
            setup.persist(new Account("patient-b", "patient_b", "hash", Role.PATIENT, null));
            setup.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    1, BigDecimal.TEN));
            setup.getTransaction().commit();
            setup.getTransaction().begin();
            firstId = new BookingService(setup).book(new CurrentActor("patient-a", Role.PATIENT, null),
                    "slot-a", Date.valueOf("2030-05-20")).getId();
            setup.getTransaction().commit();
        } finally { setup.close(); }
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> cancel = workers.submit(() -> {
                EntityManager manager = factory.createEntityManager();
                try {
                    start.await();
                    manager.getTransaction().begin();
                    new BookingService(manager).cancel(new CurrentActor("patient-a", Role.PATIENT, null),
                            firstId, Instant.parse("2030-05-21T00:59:59Z"));
                    manager.getTransaction().commit();
                    return true;
                } finally {
                    if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
                    manager.close();
                }
            });
            Future<Boolean> book = workers.submit(() -> attemptBooking(factory, start, "patient-b"));
            start.countDown();
            assertTrue(cancel.get(10, TimeUnit.SECONDS));
            boolean booked = book.get(10, TimeUnit.SECONDS);
            EntityManager verify = factory.createEntityManager();
            try {
                assertEquals("CANCELED", verify.find(Appointment.class, firstId).getStatus());
                assertEquals(booked ? 1L : 0L,
                        verify.createQuery("select count(a) from Appointment a where a.slotId = 'slot-a' "
                                + "and a.status <> 'CANCELED'", Long.class).getSingleResult());
            } finally { verify.close(); }
        } finally { workers.shutdownNow(); factory.close(); }
    }

    @Test
    void canceledHistoryFreesCapacityForAnotherPatient() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Department("department-a", "tenant-a", "全科"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "Doctor"));
            manager.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
            manager.persist(new Account("patient-b", "patient_b", "hash", Role.PATIENT, null));
            manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    1, BigDecimal.TEN));
            manager.persist(new Appointment("canceled-a", "patient-a", "slot-a", "tenant-a",
                    "doctor-a", BigDecimal.TEN, Date.valueOf("2030-05-20")));
            manager.getTransaction().commit();
            manager.getTransaction().begin();
            manager.createNativeQuery("update appointment set status = 'CANCELED' where id = 'canceled-a'").executeUpdate();
            manager.getTransaction().commit(); manager.clear();
            manager.getTransaction().begin();
            Appointment replacement = new BookingService(manager).book(
                    new CurrentActor("patient-b", Role.PATIENT, null), "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            assertEquals("patient-b", replacement.getPatientId());
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close(); factory.close();
        }
    }

    @Test
    void loginRejectsDisabledAccountAndTokenReflectsActiveDatabaseState() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            Account patient = new Account("patient-a", "patient_demo",
                    BCrypt.hashpw("Demo@123456", BCrypt.gensalt(4)), Role.PATIENT, null);
            manager.persist(patient);
            manager.getTransaction().commit();
            AuthService auth = new AuthService(manager, SECRET);

            LoginResponse login = auth.login(new LoginRequest("patient_demo", "Demo@123456"));
            assertTrue(login.getAccessToken().length() > 30);
            assertEquals("patient-a", auth.authenticate(login.getAccessToken()).getUserId());
            assertThrows(SecurityException.class, () -> auth.authenticate(login.getAccessToken() + "x"));

            manager.getTransaction().begin();
            patient.disable();
            manager.getTransaction().commit();
            assertThrows(SecurityException.class, () -> auth.authenticate(login.getAccessToken()));
            assertThrows(SecurityException.class,
                    () -> auth.login(new LoginRequest("patient_demo", "Demo@123456")));
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void disabledTenantRejectsDoctorAndHospitalLoginAndExistingTokensAcrossTransactions() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager authManager = factory.createEntityManager();
        EntityManager adminManager = factory.createEntityManager();
        try {
            adminManager.getTransaction().begin();
            adminManager.persist(new Tenant("tenant-a", "仁和云诊所"));
            adminManager.persist(new Account("doctor-a", "doctor_a",
                    BCrypt.hashpw("Demo@123456", BCrypt.gensalt(4)), Role.DOCTOR, "tenant-a"));
            adminManager.persist(new Account("hospital-a", "hospital_a",
                    BCrypt.hashpw("Demo@123456", BCrypt.gensalt(4)), Role.HOSPITAL_ADMIN, "tenant-a"));
            adminManager.getTransaction().commit();

            AuthService auth = new AuthService(authManager, SECRET);
            String doctorToken = auth.login(new LoginRequest("doctor_a", "Demo@123456")).getAccessToken();
            String hospitalToken = auth.login(new LoginRequest("hospital_a", "Demo@123456")).getAccessToken();
            assertEquals("doctor-a", auth.authenticate(doctorToken).getUserId());
            assertEquals("hospital-a", auth.authenticate(hospitalToken).getUserId());
            assertEquals("ACTIVE", authManager.find(Tenant.class, "tenant-a").getStatus());

            adminManager.getTransaction().begin();
            adminManager.createNativeQuery("update tenant set status = 'DISABLED' where id = 'tenant-a'")
                    .executeUpdate();
            adminManager.getTransaction().commit();

            assertThrows(SecurityException.class,
                    () -> auth.login(new LoginRequest("doctor_a", "Demo@123456")));
            assertThrows(SecurityException.class,
                    () -> auth.login(new LoginRequest("hospital_a", "Demo@123456")));
            assertThrows(SecurityException.class, () -> auth.authenticate(doctorToken));
            assertThrows(SecurityException.class, () -> auth.authenticate(hospitalToken));
        } finally {
            authManager.close();
            adminManager.close();
            factory.close();
        }
    }

    @Test
    void bookingDerivesTenantAndDoctorFromSlotAndRejectsDuplicateOrFullSlot() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Department("department-a", "tenant-a", "全科"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "Doctor"));
            manager.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
            manager.persist(new Account("patient-b", "patient_b", "hash", Role.PATIENT, null));
            manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    1, new BigDecimal("39.00")));
            manager.getTransaction().commit();
            BookingService booking = new BookingService(manager);
            CurrentActor patientA = new CurrentActor("patient-a", Role.PATIENT, null);
            CurrentActor patientB = new CurrentActor("patient-b", Role.PATIENT, null);

            manager.getTransaction().begin();
            Appointment created = booking.book(patientA, "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            assertEquals("tenant-a", created.getTenantId());
            assertEquals("doctor-a", created.getDoctorId());
            assertEquals(new BigDecimal("39.00"), created.getFee());

            manager.getTransaction().begin();
            assertThrows(IllegalStateException.class,
                    () -> booking.book(patientA, "slot-a", Date.valueOf("2030-05-20")));
            assertThrows(IllegalStateException.class,
                    () -> booking.book(patientB, "slot-a", Date.valueOf("2030-05-20")));
            manager.getTransaction().rollback();
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void doctorCannotReadAnotherDoctorsOrTenantsAppointments() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "仁和云诊所"));
            manager.persist(new Tenant("tenant-b", "安康云门诊"));
            manager.persist(new Department("department-a", "tenant-a", "全科"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "Doctor"));
            manager.persist(new Account("doctor-b", "doctor_b", "hash", Role.DOCTOR, "tenant-b"));
            manager.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
            manager.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    1, new BigDecimal("39.00")));
            manager.getTransaction().commit();
            BookingService booking = new BookingService(manager);
            manager.getTransaction().begin();
            booking.book(new CurrentActor("patient-a", Role.PATIENT, null), "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();

            assertEquals(1, booking.forDoctor(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-b")).size());
            assertEquals(0, booking.forDoctor(new CurrentActor("doctor-b", Role.DOCTOR, "tenant-a")).size());
            assertThrows(SecurityException.class,
                    () -> booking.forDoctor(new CurrentActor("patient-a", Role.DOCTOR, "tenant-a")));
        } finally {
            manager.close();
            factory.close();
        }
    }

    @Test
    void concurrentPatientsCannotBothTakeLastPlace() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            EntityManager setup = factory.createEntityManager();
            try {
                setup.getTransaction().begin();
                setup.persist(new Tenant("tenant-a", "仁和云诊所"));
                setup.persist(new Department("department-a", "tenant-a", "全科"));
                setup.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
                setup.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "Doctor"));
                setup.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
                setup.persist(new Account("patient-b", "patient_b", "hash", Role.PATIENT, null));
                setup.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                        Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                        1, new BigDecimal("39.00")));
                setup.getTransaction().commit();
            } finally {
                setup.close();
            }
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> first = workers.submit(() -> attemptBooking(factory, start, "patient-a"));
            Future<Boolean> second = workers.submit(() -> attemptBooking(factory, start, "patient-b"));
            start.countDown();
            int successes = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            EntityManager verify = factory.createEntityManager();
            try {
                assertEquals(1, successes);
                assertEquals(1L, verify.createQuery("select count(a) from Appointment a", Long.class)
                        .getSingleResult());
            } finally {
                verify.close();
            }
        } finally {
            workers.shutdownNow();
            factory.close();
        }
    }

    @Test
    void staleSnapshotOnOrdinaryReadCannotOverbookAfterSlotLock() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager stale = factory.createEntityManager();
        EntityManager other = factory.createEntityManager();
        try {
            other.getTransaction().begin();
            other.persist(new Tenant("tenant-a", "仁和云诊所"));
            other.persist(new Department("department-a", "tenant-a", "全科"));
            other.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            other.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "Doctor"));
            other.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
            other.persist(new Account("patient-b", "patient_b", "hash", Role.PATIENT, null));
            other.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                    Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                    1, new BigDecimal("39.00")));
            other.getTransaction().commit();

            other.getTransaction().begin();
            new BookingService(other).book(new CurrentActor("patient-a", Role.PATIENT, null),
                    "slot-a", Date.valueOf("2030-05-20"));
            other.getTransaction().commit();

            stale.getTransaction().begin();
            assertThrows(IllegalStateException.class, () -> new BookingService(withStaleUnlockedAppointmentReads(stale)).book(
                    new CurrentActor("patient-b", Role.PATIENT, null), "slot-a", Date.valueOf("2030-05-20")));
            stale.getTransaction().rollback();
        } finally {
            if (stale.getTransaction().isActive()) stale.getTransaction().rollback();
            if (other.getTransaction().isActive()) other.getTransaction().rollback();
            stale.close();
            other.close();
            factory.close();
        }
    }

    @Test
    void disablingDoctorCannotCommitAroundAnUnfinishedBooking() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch doctorRead = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        try {
            EntityManager setup = factory.createEntityManager();
            try {
                setup.getTransaction().begin();
                setup.persist(new Tenant("tenant-a", "仁和云诊所"));
                setup.persist(new Department("department-a", "tenant-a", "全科"));
                setup.persist(new Account("admin-a", "admin_a", "hash", Role.HOSPITAL_ADMIN, "tenant-a"));
                setup.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
                setup.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "王医生"));
                setup.persist(new Account("patient-a", "patient_a", "hash", Role.PATIENT, null));
                setup.persist(new ScheduleSlot("slot-a", "tenant-a", "department-a", "doctor-a",
                        Date.valueOf("2030-05-21"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"),
                        1, new BigDecimal("39.00")));
                setup.getTransaction().commit();
            } finally { setup.close(); }

            Future<Boolean> booking = workers.submit(() -> {
                EntityManager manager = factory.createEntityManager();
                try {
                    manager.getTransaction().begin();
                    new BookingService(pausingAfterDoctorRead(manager, doctorRead, releaseBooking)).book(
                            new CurrentActor("patient-a", Role.PATIENT, null), "slot-a", Date.valueOf("2030-05-20"));
                    manager.getTransaction().commit();
                    return true;
                } catch (IllegalStateException rejected) {
                    if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
                    return false;
                } finally { manager.close(); }
            });
            assertTrue(doctorRead.await(10, TimeUnit.SECONDS));
            Future<Boolean> disabling = workers.submit(() -> {
                EntityManager manager = factory.createEntityManager();
                try {
                    manager.getTransaction().begin();
                    new HospitalDoctorService(withStaleUnlockedPendingReads(manager)).update(
                            new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a"),
                            "doctor-a", "王医生", "department-a", "DISABLED");
                    manager.getTransaction().commit();
                    return true;
                } catch (IllegalStateException rejected) {
                    if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
                    return false;
                } finally { manager.close(); }
            });
            try {
                // Give the disable transaction time to reach its database check while booking is paused.
                try { disabling.get(1500, TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException expectedWhileDoctorLocked) { }
            } finally { releaseBooking.countDown(); }
            assertTrue(booking.get(10, TimeUnit.SECONDS));
            assertFalse(disabling.get(10, TimeUnit.SECONDS));
            EntityManager verify = factory.createEntityManager();
            try {
                assertEquals("ACTIVE", verify.find(Account.class, "doctor-a").getStatus());
                assertEquals(1L, verify.createQuery("select count(a) from Appointment a where a.doctorId = :doctorId "
                        + "and a.status = 'BOOKED'", Long.class).setParameter("doctorId", "doctor-a")
                        .getSingleResult());
            } finally { verify.close(); }
        } finally {
            releaseBooking.countDown();
            workers.shutdownNow();
            factory.close();
        }
    }

    private static EntityManager pausingAfterDoctorRead(EntityManager delegate, CountDownLatch read,
            CountDownLatch release) {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if ("find".equals(method.getName()) && args != null && args.length >= 2
                            && args[0] == Account.class && "doctor-a".equals(args[1])) {
                        read.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("booking was not released");
                    }
                    return result;
                });
    }

    private static EntityManager withStaleUnlockedPendingReads(EntityManager delegate) {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (!"createQuery".equals(method.getName()) || args == null || args.length != 2
                            || !String.valueOf(args[0]).contains("from Appointment a where a.doctorId")) {
                        return result;
                    }
                    TypedQuery<?> query = (TypedQuery<?>) result;
                    final LockModeType[] lock = {LockModeType.NONE};
                    return Proxy.newProxyInstance(TypedQuery.class.getClassLoader(),
                            new Class<?>[]{TypedQuery.class}, (queryProxy, queryMethod, queryArgs) -> {
                                if ("setLockMode".equals(queryMethod.getName())) {
                                    lock[0] = (LockModeType) queryArgs[0];
                                }
                                if (lock[0] != LockModeType.PESSIMISTIC_WRITE) {
                                    if ("getSingleResult".equals(queryMethod.getName())) return 0L;
                                    if ("getResultList".equals(queryMethod.getName())) return Collections.emptyList();
                                }
                                Object queryResult = invoke(query, queryMethod, queryArgs);
                                return queryResult == query ? queryProxy : queryResult;
                            });
                });
    }

    private static EntityManager withStaleUnlockedAppointmentReads(EntityManager delegate) {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class}, (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (!"createQuery".equals(method.getName()) || args == null || args.length != 2
                            || !String.valueOf(args[0]).startsWith("select a from Appointment a where a.slotId")) {
                        return result;
                    }
                    TypedQuery<?> query = (TypedQuery<?>) result;
                    final LockModeType[] lock = {LockModeType.NONE};
                    return Proxy.newProxyInstance(TypedQuery.class.getClassLoader(),
                            new Class<?>[]{TypedQuery.class}, (queryProxy, queryMethod, queryArgs) -> {
                                if ("setLockMode".equals(queryMethod.getName())) {
                                    lock[0] = (LockModeType) queryArgs[0];
                                }
                                if ("getResultList".equals(queryMethod.getName())
                                        && lock[0] != LockModeType.PESSIMISTIC_WRITE) {
                                    return Collections.emptyList();
                                }
                                Object queryResult = invoke(query, queryMethod, queryArgs);
                                return queryResult == query ? queryProxy : queryResult;
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static boolean attemptBooking(EntityManagerFactory factory, CountDownLatch start, String patientId)
            throws InterruptedException {
        EntityManager manager = factory.createEntityManager();
        try {
            start.await();
            manager.getTransaction().begin();
            new BookingService(manager).book(new CurrentActor(patientId, Role.PATIENT, null),
                    "slot-a", Date.valueOf("2030-05-20"));
            manager.getTransaction().commit();
            return true;
        } catch (IllegalStateException rejected) {
            if (manager.getTransaction().isActive()) {
                manager.getTransaction().rollback();
            }
            if (!"slot full".equals(rejected.getMessage())) throw rejected;
            return false;
        } finally {
            manager.close();
        }
    }
}
