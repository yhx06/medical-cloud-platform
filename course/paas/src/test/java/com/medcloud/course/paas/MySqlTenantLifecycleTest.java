package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.consultation.*;
import com.medcloud.course.paas.order.*;
import com.medcloud.course.paas.schedule.*;
import com.medcloud.course.paas.tenant.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.persistence.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MySqlTenantLifecycleTest extends MySqlPlatformFixture {
    @ParameterizedTest
    @ValueSource(strings={"book","cancel","start","complete","pay","repeatPay","createDepartment",
            "updateDepartment","createDoctor","updateDoctor","createSlot","updateSlot"})
    void businessLockDelaysDisable(String operation) throws Exception {
        CountDownLatch readAcquired = new CountDownLatch(1), release = new CountDownLatch(1), competing = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        Future<?> writer = null;
        Future<TenantView> disable = null;
        try {
            writer = threads.submit(() -> inTransaction(em -> {
                AtomicBoolean first = new AtomicBoolean(true);
                EntityManager observed = proxy(em, (method,args,result) -> {
                    if (isLock(args)) {
                        if (first.getAndSet(false)) assertTrue(isTenant(args), "hospital must precede every business lock");
                        if (method.equals("refresh") && isTenant(args)) readAcquired.countDown();
                    }
                }, null);
                write(observed, operation);
                assertEquals(0L, readAcquired.getCount(), "real service acquired hospital read lock");
                await(release);
                return null;
            }));
            assertTrue(readAcquired.await(10,TimeUnit.SECONDS));
            disable = threads.submit(() -> inTransaction(em -> {
                EntityManager observed = proxy(em, null, (method,args,result) -> {
                    if (isTenant(args) && Arrays.asList(args).contains(LockModeType.PESSIMISTIC_WRITE)) competing.countDown();
                });
                return new PlatformTenantService(observed).changeStatus(PLATFORM,"tenant-a",new TenantStatusRequest("DISABLED","ACTIVE"));
            }));
            assertTrue(competing.await(10,TimeUnit.SECONDS), "disable reached the competing database lock");
            final Future<TenantView> waiting = disable;
            assertThrows(TimeoutException.class, () -> waiting.get(200,TimeUnit.MILLISECONDS));
            release.countDown();
            writer.get(10,TimeUnit.SECONDS);
            assertEquals("DISABLED",disable.get(10,TimeUnit.SECONDS).getStatus());
            assertEquals("DISABLED", inTransaction(em -> em.find(Tenant.class,"tenant-a").getStatus()));
        } finally {
            release.countDown();
            if (writer != null && !writer.isDone()) writer.cancel(true);
            if (disable != null && !disable.isDone()) disable.cancel(true);
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(10,TimeUnit.SECONDS), "all transactions and workers terminated");
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"book","cancel","start","complete","pay","repeatPay","createDepartment",
            "updateDepartment","createDoctor","updateDoctor","createSlot","updateSlot"})
    void disableCommittedBeforeStaleWriterRejectsMutation(String operation) {
        EntityManager stale = manager();
        try {
            stale.getTransaction().begin();
            assertEquals("ACTIVE",stale.find(Tenant.class,"tenant-a").getStatus());
            // Plain SELECT establishes an old REPEATABLE READ snapshot and managed ACTIVE entity.
            stale.createNativeQuery("select count(*) from appointment").getSingleResult();
            inTransaction(em -> new PlatformTenantService(em).changeStatus(PLATFORM,"tenant-a",new TenantStatusRequest("DISABLED","ACTIVE")));
            List<String> before = inTransaction(this::snapshot);
            AtomicBoolean refreshed = new AtomicBoolean(false);
            AtomicBoolean first = new AtomicBoolean(true);
            EntityManager observed = proxy(stale,(method,args,result) -> {
                if (isLock(args)) {
                    if (first.getAndSet(false)) assertTrue(isTenant(args), "hospital lock must be first");
                    if (method.equals("refresh") && isTenant(args)) {
                        refreshed.set(true);
                        assertEquals("DISABLED",((Tenant)args[0]).getStatus(), "refresh must use current locking read");
                    }
                }
            },null);
            assertThrows(IllegalStateException.class, () -> write(observed,operation));
            assertTrue(refreshed.get());
            stale.getTransaction().rollback();
            assertEquals(before,inTransaction(this::snapshot),"rejected write preserves every business column");
        } finally { close(stale); }
    }

    private void write(EntityManager em,String operation) {
        CurrentActor hospital = new CurrentActor("admin",Role.HOSPITAL_ADMIN,"tenant-a");
        CurrentActor doctor = new CurrentActor("doctor",Role.DOCTOR,"tenant-a");
        CurrentActor patient = new CurrentActor("patient",Role.PATIENT,null);
        Instant before = Instant.parse("2030-05-20T00:00:00Z");
        SlotRequest slot = new SlotRequest("department","doctor",Date.valueOf("2030-05-21"),Time.valueOf("09:00:00"),Time.valueOf("10:00:00"),20,new BigDecimal("39.10"),"PUBLISHED");
        switch(operation) {
            case "book": new BookingService(em).book(new CurrentActor("other",Role.PATIENT,null),"slot",Date.valueOf("2030-05-20")); break;
            case "cancel": new BookingService(em).cancel(patient,"booked",before); break;
            case "start": new ClinicalService(em).start(doctor,"booked",before); break;
            case "complete": new ClinicalService(em).complete(doctor,"progress","fictional","course"); break;
            case "pay": new PaymentService(em).pay(patient,"order-unpaid"); break;
            case "repeatPay": new PaymentService(em).pay(patient,"order-paid"); break;
            case "createDepartment": new HospitalDepartmentService(em).create(hospital,"New"); break;
            case "updateDepartment": new HospitalDepartmentService(em).update(hospital,"department","Updated","ACTIVE"); break;
            case "createDoctor": new HospitalDoctorService(em).create(hospital,"new_doctor","New","department"); break;
            case "updateDoctor": new HospitalDoctorService(em).update(hospital,"doctor","Updated","department","ACTIVE"); break;
            case "createSlot": slot.setStartTime(Time.valueOf("11:00:00")); slot.setEndTime(Time.valueOf("12:00:00")); new SlotAdminService(em).create(hospital,slot); break;
            case "updateSlot": new SlotAdminService(em).update(hospital,"slot",slot); break;
            default: throw new AssertionError(operation);
        }
    }
}

/** Test-only factory: the URL guard runs before Hibernate can issue destructive DDL. */
abstract class MySqlPlatformFixture {
    static final CurrentActor PLATFORM = new CurrentActor("platform",Role.PLATFORM_ADMIN,null);
    EntityManagerFactory factory;
    @BeforeEach void mysqlFixture() {
        String url = System.getenv("COURSE_MYSQL_TEST_URL");
        assumeTrue(url != null,"Disposable MySQL unavailable; run platform-statistics.Tests.ps1");
        assertTrue(url.matches("^jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/course_platform_test_[a-f0-9]{32}\\?[^#]*$"),"refuse create-drop outside dedicated local fixture");
        Map<String,Object> settings = new HashMap<String,Object>();
        settings.put("javax.persistence.jdbc.driver","com.mysql.cj.jdbc.Driver");
        settings.put("javax.persistence.jdbc.url",url);
        settings.put("javax.persistence.jdbc.user",System.getenv("COURSE_MYSQL_TEST_USER"));
        settings.put("javax.persistence.jdbc.password",System.getenv("COURSE_MYSQL_TEST_PASSWORD"));
        settings.put("hibernate.dialect","org.hibernate.dialect.MySQL5InnoDBDialect");
        settings.put("hibernate.connection.isolation","4");
        settings.put("hibernate.hbm2ddl.auto","create-drop");
        factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest",settings);
        inTransaction(em -> {
            em.persist(new Tenant("tenant-a","A")); em.persist(new Tenant("tenant-b","B"));
            Tenant empty = new Tenant("tenant-c","C"); empty.setStatus("DISABLED"); em.persist(empty);
            em.persist(new Department("department","tenant-a","General"));
            for (String id : Arrays.asList("admin","doctor","patient","other","platform")) {
                Role role = id.equals("admin") ? Role.HOSPITAL_ADMIN : id.equals("doctor") ? Role.DOCTOR : id.equals("platform") ? Role.PLATFORM_ADMIN : Role.PATIENT;
                em.persist(new Account(id,id,"hash",role,(role==Role.HOSPITAL_ADMIN || role==Role.DOCTOR) ? "tenant-a" : null));
            }
            em.persist(new DoctorProfile("doctor","tenant-a","department","Doctor"));
            em.persist(new ScheduleSlot("slot","tenant-a","department","doctor",Date.valueOf("2030-05-21"),Time.valueOf("09:00:00"),Time.valueOf("10:00:00"),20,new BigDecimal("39.10")));
            for (String id : Arrays.asList("booked","progress","unpaid","paid")) {
                Appointment a = new Appointment(id,"patient","slot","tenant-a","doctor",new BigDecimal("39.10"),Date.valueOf("2030-05-20"),Instant.parse("2030-05-20T00:00:00Z"));
                if (!id.equals("booked")) a.start(Instant.parse("2030-05-20T01:00:00Z"));
                if (id.equals("unpaid") || id.equals("paid")) {
                    a.complete();
                    em.persist(new ClinicalRecord("record-"+id,id,"tenant-a","doctor","patient",null,"fictional","course",null,Instant.parse("2030-05-20T02:00:00Z")));
                    PaymentOrder order = new PaymentOrder("order-"+id,id,"patient","tenant-a",new BigDecimal("39.10"));
                    if (id.equals("paid")) order.pay(Instant.parse("2030-05-20T03:00:00Z"));
                    em.persist(order);
                }
                em.persist(a);
            }
            return null;
        });
    }
    interface Work<T> { T run(EntityManager em); }
    <T> T inTransaction(Work<T> work) {
        EntityManager em = manager();
        try { em.getTransaction().begin(); T value=work.run(em); em.getTransaction().commit(); return value; }
        finally { close(em); }
    }
    EntityManager manager() {
        EntityManager em=factory.createEntityManager();
        em.getTransaction().begin();
        em.createNativeQuery("set session innodb_lock_wait_timeout = 5").executeUpdate();
        assertEquals("REPEATABLE-READ",em.createNativeQuery("select @@session.transaction_isolation").getSingleResult());
        em.getTransaction().commit(); return em;
    }
    static void close(EntityManager em) { if(em.getTransaction().isActive())em.getTransaction().rollback(); em.close(); }
    @AfterEach void closeFactory() { if(factory!=null)factory.close(); }
    interface Observation { void call(String method,Object[] args,Object result); }
    static EntityManager proxy(EntityManager actual,Observation after,Observation before) {
        return (EntityManager)Proxy.newProxyInstance(EntityManager.class.getClassLoader(),new Class<?>[]{EntityManager.class},(p,m,args) -> {
            Object[] a=args==null ? new Object[0] : args;
            if(before!=null)before.call(m.getName(),a,null);
            try { Object result=m.invoke(actual,args); if(after!=null)after.call(m.getName(),a,result); return result; }
            catch(InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    static boolean isLock(Object[] args) { return Arrays.asList(args).contains(LockModeType.PESSIMISTIC_READ) || Arrays.asList(args).contains(LockModeType.PESSIMISTIC_WRITE); }
    static boolean isTenant(Object[] args) { return args.length>0 && (args[0]==Tenant.class || args[0] instanceof Tenant); }
    static void await(CountDownLatch latch) { try { assertTrue(latch.await(10,TimeUnit.SECONDS),"bounded transaction coordination"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
    List<String> snapshot(EntityManager em) {
        List<String> result=new ArrayList<String>();
        for(String table:Arrays.asList("user_account","department","doctor_profile","schedule_slot","appointment","clinical_record","payment_order")) {
            List<String> rows=new ArrayList<String>();
            for(Object row:em.createNativeQuery("select * from "+table).getResultList())rows.add(Arrays.deepToString((Object[])row));
            Collections.sort(rows); result.add(table+rows);
        }
        return result;
    }
}
