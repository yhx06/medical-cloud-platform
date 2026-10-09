package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.order.*;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.*;
import javax.persistence.*;
import javax.ws.rs.core.MultivaluedHashMap;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real resource-local MySQL tests. Container JTA remains a separate HTTP acceptance gate. */
class MySqlAuditTransactionTest {
    private static final Instant AT = Instant.parse("2030-05-20T16:01:02.123456Z");
    private static final CurrentActor ADMIN = new CurrentActor("admin", Role.HOSPITAL_ADMIN, "tenant");
    private EntityManagerFactory factory;
    private TimeZone priorZone;

    @BeforeEach void open() {
        String url = System.getenv("COURSE_MYSQL_TEST_URL");
        assumeTrue(url != null, "Run audit-acceptance.Tests.ps1 for a disposable instance");
        assertTrue(url.matches("^jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/course_audit_test_[a-f0-9]{32}\\?[^#]*$"), "Refuse create-drop outside unique loopback audit fixture");
        int port = Integer.parseInt(url.substring("jdbc:mysql://127.0.0.1:".length(), url.indexOf('/', "jdbc:mysql://".length())));
        assertTrue(port > 0 && port <= 65535 && port != 3318, "Refuse protected runtime port");
        priorZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        Map<String,Object> settings = new HashMap<>();
        settings.put("javax.persistence.jdbc.driver", "com.mysql.cj.jdbc.Driver");
        settings.put("javax.persistence.jdbc.url", url);
        settings.put("javax.persistence.jdbc.user", System.getenv("COURSE_MYSQL_TEST_USER"));
        settings.put("javax.persistence.jdbc.password", System.getenv("COURSE_MYSQL_TEST_PASSWORD"));
        settings.put("hibernate.dialect", "org.hibernate.dialect.MySQL5InnoDBDialect");
        settings.put("hibernate.connection.isolation", "4");
        settings.put("hibernate.hbm2ddl.auto", "create-drop");
        factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest", settings);
        tx(em -> {
            em.persist(new Tenant("tenant", "Audit fixture"));
            em.persist(new Account("admin", "audit_admin", "unused", Role.HOSPITAL_ADMIN, "tenant"));
            em.persist(new Account("patient", "audit_patient", "unused", Role.PATIENT, null));
            em.persist(new PaymentOrder("order", "appointment", "patient", "tenant", BigDecimal.TEN));
            return null;
        });
    }
    @AfterEach void close() {
        try { if (factory != null) factory.close(); }
        finally { if (priorZone != null) TimeZone.setDefault(priorZone); }
    }
    @Test void committedSuccessVisible() {
        AuditContext c = context(AuditEventType.DEPARTMENT_UPDATE);
        tx(em -> { em.find(Tenant.class,"tenant").setStatus("DISABLED"); new AuditJournal(em).append(c,"department",AuditResult.SUCCESS,AT); return null; });
        tx(em -> {
            assertEquals("DISABLED", em.find(Tenant.class,"tenant").getStatus());
            AuditEvent row = row(em,c);
            assertEquals(AuditResult.SUCCESS,row.getResult()); assertEquals(AuditReasonCode.OK,row.getReasonCode());
            assertEquals("department",row.getObjectId()); assertEquals(AT,row.getRecordedAt().toInstant()); return null;
        });
    }
    @Test void rolledBackMutationHasNoSuccess() {
        AuditContext c = context(AuditEventType.TENANT_STATUS_CHANGE);
        EntityManager em = factory.createEntityManager();
        try {
            em.getTransaction().begin(); em.find(Tenant.class,"tenant").setStatus("DISABLED");
            new AuditJournal(em).append(c,"tenant",AuditResult.SUCCESS,AT); em.getTransaction().rollback();
        } finally { release(em); }
        tx(check -> { assertEquals("ACTIVE",check.find(Tenant.class,"tenant").getStatus()); assertEquals(0L,count(check)); return null; });
    }
    @Test void forcedSuccessInsertFailureRollsBackBusiness() {
        AuditContext c = context(AuditEventType.DEPARTMENT_UPDATE);
        tx(em -> {
            em.createNativeQuery("CREATE TRIGGER course_audit_success_fault BEFORE INSERT ON audit_event FOR EACH ROW BEGIN IF NEW.actor_id='admin' AND NEW.event_type='DEPARTMENT_UPDATE' AND NEW.result IN ('SUCCESS','NO_CHANGE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AUDIT_ACCEPTANCE_FAULT'; END IF; END").executeUpdate();
            return null;
        });
        try {
            assertThrows(AuditInfrastructureException.class, () -> tx(em -> {
                em.find(Tenant.class,"tenant").setStatus("DISABLED");
                new AuditJournal(em).append(c,"department",AuditResult.SUCCESS,AT); return null;
            }));
            tx(em -> { new AuditFailureJournal(em).append(c,AuditReasonCode.INTERNAL_ERROR,AT); return null; });
            tx(em -> {
                assertEquals("ACTIVE",em.find(Tenant.class,"tenant").getStatus()); assertEquals(1L,count(em));
                assertEquals(AuditResult.FAILED,row(em,c).getResult()); assertNull(row(em,c).getObjectId()); return null;
            });
        } finally { tx(em -> { em.createNativeQuery("DROP TRIGGER course_audit_success_fault").executeUpdate(); return null; }); }
    }
    @Test void duplicateRequestEventIsRejected() {
        AuditContext c = context(AuditEventType.DEPARTMENT_UPDATE);
        tx(em -> { new AuditJournal(em).append(c,"first",AuditResult.SUCCESS,AT); return null; });
        assertThrows(AuditInfrastructureException.class, () -> tx(em -> { new AuditJournal(em).append(c,"second",AuditResult.SUCCESS,AT); return null; }));
        tx(em -> { assertEquals(1L,count(em)); assertEquals("first",row(em,c).getObjectId()); return null; });
    }
    @Test void failureStoresNoUntrustedObject() {
        AuditContext c = AuditContext.create(AuditEventType.APPOINTMENT_CANCEL,UUID.randomUUID().toString());
        c.attachActor(new CurrentActor("patient",Role.PATIENT,null));
        tx(em -> { new AuditFailureJournal(em).append(c,AuditReasonCode.FORBIDDEN,AT); return null; });
        tx(em -> {
            AuditEvent row = row(em,c); assertEquals(AuditResult.FAILED,row.getResult());
            assertEquals(AuditReasonCode.FORBIDDEN,row.getReasonCode()); assertEquals("patient",row.getActorId());
            assertNull(row.getObjectId()); assertNull(row.getTenantId());
            List<?> columns = em.createNativeQuery("select column_name from information_schema.columns where table_schema=database() and table_name='audit_event'").getResultList();
            assertEquals(new HashSet<>(Arrays.asList("id","request_id","actor_id","actor_role","tenant_id","event_type","object_type","object_id","result","reason_code","recorded_at")),new HashSet<>(columns));
            return null;
        });
    }
    @Test void shanghaiMidnightRangeCorrect() {
        String[] instants = {"2030-05-19T15:59:59.999999Z","2030-05-19T16:00:00Z","2030-05-20T15:59:59.999999Z","2030-05-20T16:00:00Z"};
        Set<String> included = new HashSet<>();
        tx(em -> {
            for(int i=0;i<instants.length;i++) {
                AuditContext c = context(AuditEventType.DEPARTMENT_UPDATE); if(i==1||i==2) included.add(c.getRequestId());
                new AuditJournal(em).append(c,"department",AuditResult.SUCCESS,Instant.parse(instants[i]));
            } return null;
        });
        AuditPage page = tx(em -> new AuditQueryService(em).hospital(ADMIN,query()));
        assertEquals(2L,page.getTotal()); assertEquals(2,page.getItems().size());
        Set<String> actual = new HashSet<>(); for(AuditEventView item:page.getItems()) actual.add(item.getRequestId());
        assertEquals(included,actual); assertEquals("Asia/Shanghai",page.getTimezone());
    }
    @Test void repeatPaymentKeepsTimestampAndNoChange() {
        CurrentActor patient = new CurrentActor("patient",Role.PATIENT,null);
        AuditContext first = AuditContext.create(AuditEventType.PAYMENT_PAY,UUID.randomUUID().toString());
        AuditContext repeat = AuditContext.create(AuditEventType.PAYMENT_PAY,UUID.randomUUID().toString());
        tx(em -> { new PaymentService(em).pay(patient,"order",first); return null; });
        // Compare the committed MySQL value, not the pre-flush JVM millisecond value.
        java.sql.Timestamp paid = tx(em -> em.find(PaymentOrder.class,"order").getPaidAt());
        java.sql.Timestamp repeated = tx(em -> new PaymentService(em).pay(patient,"order",repeat).getPaidAt());
        assertNotNull(paid); assertEquals(paid,repeated);
        tx(em -> {
            assertEquals(2L,count(em)); assertEquals(AuditResult.SUCCESS,row(em,first).getResult());
            assertEquals(AuditResult.NO_CHANGE,row(em,repeat).getResult()); assertEquals(AuditReasonCode.UNCHANGED,row(em,repeat).getReasonCode());
            assertEquals("order",row(em,repeat).getObjectId()); return null;
        });
    }
    @Test void countAndRowsShareRepeatableReadSnapshot() throws Exception {
        tx(em -> { new AuditJournal(em).append(context(AuditEventType.DEPARTMENT_UPDATE),"initial",AuditResult.SUCCESS,Instant.parse("2030-05-20T01:00:00Z")); return null; });
        CountDownLatch counted = new CountDownLatch(1), committed = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> writer = worker.submit(() -> {
            await(counted); tx(em -> { new AuditJournal(em).append(context(AuditEventType.DEPARTMENT_UPDATE),"later",AuditResult.SUCCESS,Instant.parse("2030-05-20T02:00:00Z")); return null; }); committed.countDown();
        });
        try {
            AuditPage snapshot = tx(em -> {
                em.unwrap(org.hibernate.Session.class).doWork(connection -> assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation()));
                AtomicBoolean paused = new AtomicBoolean();
                EntityManager observed = (EntityManager)Proxy.newProxyInstance(EntityManager.class.getClassLoader(),new Class<?>[]{EntityManager.class},(proxy,method,args) -> {
                    Object value = invoke(method,em,args);
                    if(method.getName().equals("createNativeQuery") && ((String)args[0]).startsWith("select count(*) from audit_event")) {
                        Query original = (Query)value;
                        return Proxy.newProxyInstance(Query.class.getClassLoader(),new Class<?>[]{Query.class},(qp,qm,qa) -> {
                            Object result = invoke(qm,original,qa);
                            if(qm.getName().equals("getSingleResult") && paused.compareAndSet(false,true)) { counted.countDown(); await(committed); }
                            return result==original ? qp : result;
                        });
                    } return value;
                });
                return new AuditQueryService(observed).hospital(ADMIN,query());
            });
            writer.get(10,TimeUnit.SECONDS);
            assertEquals(1L,snapshot.getTotal()); assertEquals(1,snapshot.getItems().size());
            AuditPage next = tx(em -> new AuditQueryService(em).hospital(ADMIN,query()));
            assertEquals(2L,next.getTotal()); assertEquals(2,next.getItems().size());
        } finally { counted.countDown(); writer.cancel(true); worker.shutdownNow(); assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS)); }
    }
    private AuditContext context(AuditEventType event) { AuditContext c=AuditContext.create(event,UUID.randomUUID().toString()); c.attachActor(ADMIN); return c; }
    private AuditQuery query() {
        MultivaluedHashMap<String,String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("from","2030-05-20"); parameters.putSingle("to","2030-05-20"); return AuditQuery.parse(parameters,false);
    }
    private AuditEvent row(EntityManager em,AuditContext c) { return em.createQuery("select e from AuditEvent e where e.requestId=:id",AuditEvent.class).setParameter("id",c.getRequestId()).getSingleResult(); }
    private long count(EntityManager em) { return em.createQuery("select count(e) from AuditEvent e",Long.class).getSingleResult(); }
    private interface Work<T> { T run(EntityManager em); }
    private <T> T tx(Work<T> work) {
        EntityManager em=factory.createEntityManager();
        try { em.getTransaction().begin(); T result=work.run(em); em.getTransaction().commit(); return result; } finally { release(em); }
    }
    private static void release(EntityManager em) { try { if(em.getTransaction().isActive()) em.getTransaction().rollback(); } finally { em.close(); } }
    private static Object invoke(Method method,Object target,Object[] args) throws Throwable { try { return method.invoke(target,args); } catch(InvocationTargetException error) { throw error.getCause(); } }
    private static void await(CountDownLatch latch) { try { assertTrue(latch.await(10,TimeUnit.SECONDS),"Competing real transaction completed"); } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); } }
}
