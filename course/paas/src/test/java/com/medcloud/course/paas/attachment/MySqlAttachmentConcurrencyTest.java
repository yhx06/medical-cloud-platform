package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.consultation.ClinicalService;
import com.medcloud.course.paas.tenant.Tenant;
import javax.persistence.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real MySQL 8 RESOURCE_LOCAL evidence. This does not claim container/JTA validation. */
abstract class MySqlAttachmentFixture extends AttachmentFixture {
    @Override protected Map<String,Object> settings() {
        String url=System.getenv("COURSE_MYSQL_TEST_URL");
        assumeTrue(url!=null,"Requires explicitly owned disposable attachment fixture");
        assertTrue(url.matches("^jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/course_attachment_test_[a-f0-9]{32}\\?[^#]*$"),"Refuse non-unique/non-loopback database");
        int port=Integer.parseInt(url.substring("jdbc:mysql://127.0.0.1:".length(),url.indexOf('/',"jdbc:mysql://".length())));
        assertTrue(port>0 && port<=65535 && port!=3318);
        Map<String,Object> map=new HashMap<>();
        map.put("javax.persistence.jdbc.driver","com.mysql.cj.jdbc.Driver"); map.put("javax.persistence.jdbc.url",url);
        map.put("javax.persistence.jdbc.user",System.getenv("COURSE_MYSQL_TEST_USER")); map.put("javax.persistence.jdbc.password",System.getenv("COURSE_MYSQL_TEST_PASSWORD"));
        map.put("hibernate.dialect","org.hibernate.dialect.MySQL5InnoDBDialect"); map.put("hibernate.connection.isolation","4");
        map.put("hibernate.hbm2ddl.auto","validate"); return map;
    }
    @Override protected void beforeSeed() {
        tx(em -> {
            em.unwrap(org.hibernate.Session.class).doWork(c -> assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,c.getTransactionIsolation()));
            // Only this guarded unique test database contains these fictional test rows.
            for(String entity:Arrays.asList("AuditEvent","FileMetadata","PaymentOrder","ClinicalRecord","Appointment","ScheduleSlot","DoctorProfile","Department","Account","Tenant")) em.createQuery("delete from "+entity).executeUpdate();
            return null;
        });
    }
    static void await(CountDownLatch latch) { try { assertTrue(latch.await(15,TimeUnit.SECONDS),"Barrier reached"); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); } }
    static Object invoke(Method method,Object target,Object[] args) throws Throwable { try { return method.invoke(target,args); } catch(InvocationTargetException ex) { throw ex.getCause(); } }
    /** Executes two real transactions, observes an actual InnoDB lock wait, then releases the first commit. */
    void ordered(String label,Function<EntityManager,?> first,Function<EntityManager,?> second,String rejectedCode) throws Exception {
        CountDownLatch holding=new CountDownLatch(1), release=new CountDownLatch(1), contenderConnected=new CountDownLatch(1);
        AtomicLong connection=new AtomicLong(); ExecutorService threads=Executors.newFixedThreadPool(2);
        Future<?> one=threads.submit(() -> tx(em -> { Object value=first.apply(em); em.flush(); holding.countDown(); await(release); return value; }));
        Future<?> two=null;
        try {
            await(holding);
            two=threads.submit(() -> tx(em -> { connection.set(((Number)em.createNativeQuery("select connection_id()").getSingleResult()).longValue()); contenderConnected.countDown(); return second.apply(em); }));
            await(contenderConnected);
            String observerUrl=System.getenv("COURSE_MYSQL_OBSERVER_URL");
            String observerUser=System.getenv("COURSE_MYSQL_OBSERVER_USER");
            String observerPassword=System.getenv("COURSE_MYSQL_OBSERVER_PASSWORD");
            assertNotNull(observerUrl,"A separate read-only lock observer is required");
            assertNotNull(observerUser,"A separate read-only lock observer is required");
            assertNotNull(observerPassword,"A separate read-only lock observer is required");
            assertTrue(observerUrl.matches("^jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/performance_schema\\?[^#]*$"),"Observer must use loopback performance_schema only");
            boolean waiting=false;
            try (java.sql.Connection observer=java.sql.DriverManager.getConnection(observerUrl,observerUser,observerPassword);
                 java.sql.PreparedStatement query=observer.prepareStatement("select count(*) from data_lock_waits w join threads t on t.THREAD_ID=w.REQUESTING_THREAD_ID where t.PROCESSLIST_ID=?")) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(System.nanoTime()<deadline) {
                    query.setLong(1,connection.get());
                    try (java.sql.ResultSet result=query.executeQuery()) {
                        assertTrue(result.next());
                        if(result.getLong(1)>0) { waiting=true; break; }
                    }
                    if(two.isDone()) break;
                    Thread.yield();
                }
            } catch(java.sql.SQLException ex) { throw new AssertionError("Read-only lock observer failed",ex); }
            assertTrue(waiting,label+": actual InnoDB lock wait before first commit");
            System.out.println("LOCK_BARRIER "+label+": wait observed, first commit released");
            release.countDown(); one.get(15,TimeUnit.SECONDS);
            if(rejectedCode==null) two.get(15,TimeUnit.SECONDS);
            else {
                final Future<?> rejected=two;
                ExecutionException error=assertThrows(ExecutionException.class,() -> rejected.get(15,TimeUnit.SECONDS));
                assertTrue(error.getCause() instanceof AttachmentException,error.getCause().getClass().getName());
                assertEquals(rejectedCode,((AttachmentException)error.getCause()).getCode());
            }
        } finally { release.countDown(); one.cancel(true); if(two!=null) two.cancel(true); threads.shutdownNow(); assertTrue(threads.awaitTermination(20,TimeUnit.SECONDS)); }
    }
}

public class MySqlAttachmentConcurrencyTest extends MySqlAttachmentFixture {
    @Test void concurrentReservationsRespectUploaderQuota() throws Exception {
        prepare("appointment1");
        ordered("uploader quota across appointments",em -> new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment2",request(UUID.randomUUID().toString()),NOW),em -> new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment3",request(UUID.randomUUID().toString()),NOW),"FILE_LIMIT_REACHED");
        assertEquals(2L,(long)tx(em -> em.createQuery("select count(f) from FileMetadata f",Long.class).getSingleResult()));
    }
    @Test void concurrentSameKeyReturnsOneIntent() throws Exception {
        String key=UUID.randomUUID().toString();
        AtomicReference<String> first=new AtomicReference<>(),second=new AtomicReference<>();
        ordered("same key",em -> { UploadOperationView v=new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment1",request(key),NOW); first.set(v.getUploadId()); return v; },em -> { UploadOperationView v=new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment1",request(key),NOW); second.set(v.getUploadId()); return v; },null);
        assertEquals(first.get(),second.get()); assertEquals(1L,(long)tx(em -> em.createQuery("select count(f) from FileMetadata f",Long.class).getSingleResult()));
    }
    @Test void completionWinsAgainstPatientPublish() throws Exception { clinicalVsPublish(true); }
    @Test void publishWinsAgainstCompletion() throws Exception { clinicalVsPublish(false); }
    private void clinicalVsPublish(boolean completionFirst) throws Exception {
        tx(em -> new ClinicalService(em).start(DOCTOR,"appointment1",NOW)); String id=prepare("appointment1"); UploadAttempt attempt=claim(id);
        Function<EntityManager,?> complete=em -> new ClinicalService(em).complete(DOCTOR,"appointment1",null,"Demo","Demo",null,NOW);
        Function<EntityManager,?> publish=em -> new AttachmentDatabaseService(em).publish(PATIENT,id,attempt.getAttemptToken(),validated(),NOW,context(AuditEventType.FILE_UPLOAD));
        ordered(completionFirst?"completion before publication":"publication before completion",completionFirst?complete:publish,completionFirst?publish:complete,completionFirst?"FILE_STATE_CONFLICT":null);
        assertEquals(completionFirst?AttachmentState.UPLOADING:AttachmentState.AVAILABLE,tx(em -> em.find(FileMetadata.class,id).state));
        assertEquals("COMPLETED",tx(em -> em.find(Appointment.class,"appointment1").getStatus()));
    }
    @Test void startWinsAgainstDelete() throws Exception { startVsDelete(true); }
    @Test void deleteWinsAgainstStart() throws Exception { startVsDelete(false); }
    private void startVsDelete(boolean startFirst) throws Exception {
        String id=prepare("appointment1"); publish(id,claim(id));
        Function<EntityManager,?> start=em -> new ClinicalService(em).start(DOCTOR,"appointment1",NOW);
        Function<EntityManager,?> delete=em -> new AttachmentDatabaseService(em).logicalDelete(PATIENT,id,NOW,context(AuditEventType.FILE_DELETE));
        ordered(startFirst?"start before delete":"delete before start",startFirst?start:delete,startFirst?delete:start,startFirst?"FILE_STATE_CONFLICT":null);
        assertEquals(startFirst?AttachmentState.AVAILABLE:AttachmentState.DELETE_PENDING,tx(em -> em.find(FileMetadata.class,id).state));
    }
    @Test void hospitalFreezeWinsAgainstPublish() throws Exception {
        String id=prepare("appointment1"); UploadAttempt attempt=claim(id);
        ordered("hospital freeze before publication",em -> { Tenant t=em.find(Tenant.class,"tenant",LockModeType.PESSIMISTIC_WRITE); em.refresh(t,LockModeType.PESSIMISTIC_WRITE); t.setStatus("DISABLED"); return t; },em -> new AttachmentDatabaseService(em).publish(PATIENT,id,attempt.getAttemptToken(),validated(),NOW,context(AuditEventType.FILE_UPLOAD)),"FILE_STATE_CONFLICT");
        assertEquals(AttachmentState.UPLOADING,tx(em -> em.find(FileMetadata.class,id).state));
    }
    @Test void downloadReadLockSerializesWithLogicalDelete() throws Exception {
        String id=prepare("appointment1"); publish(id,claim(id)); AttachmentSnapshot snapshot=tx(em -> new AttachmentDatabaseService(em).downloadCandidate(PATIENT,id));
        ordered("authorization before delete",em -> { new AttachmentDatabaseService(em).commitDownloadAuthorization(PATIENT,id,snapshot.getVersion(),NOW.plusSeconds(300),NOW,context(AuditEventType.FILE_DOWNLOAD_AUTHORIZE)); return null; },em -> new AttachmentDatabaseService(em).logicalDelete(PATIENT,id,NOW,context(AuditEventType.FILE_DELETE)),null);
        assertEquals(AttachmentState.DELETE_PENDING,tx(em -> em.find(FileMetadata.class,id).state));
    }
    @Test void logicalDeleteWinsAgainstDownloadAuthorization() throws Exception {
        String id=prepare("appointment1"); publish(id,claim(id)); AttachmentSnapshot snapshot=tx(em -> new AttachmentDatabaseService(em).downloadCandidate(PATIENT,id));
        ordered("delete before authorization",em -> new AttachmentDatabaseService(em).logicalDelete(PATIENT,id,NOW,context(AuditEventType.FILE_DELETE)),em -> { new AttachmentDatabaseService(em).commitDownloadAuthorization(PATIENT,id,snapshot.getVersion(),NOW.plusSeconds(300),NOW,context(AuditEventType.FILE_DOWNLOAD_AUTHORIZE)); return null; },"FILE_STATE_CONFLICT");
    }
    @Test void countAndRowsShareRepeatableReadSnapshot() throws Exception {
        String id=prepare("appointment1"); publish(id,claim(id));
        String later=prepare("appointment1"); UploadAttempt attempt=claim(later);
        CountDownLatch counted=new CountDownLatch(1),committed=new CountDownLatch(1); ExecutorService worker=Executors.newSingleThreadExecutor();
        Future<?> writer=worker.submit(() -> { await(counted); publish(later,attempt); committed.countDown(); });
        try {
            AttachmentPage page=tx(em -> {
                AtomicBoolean paused=new AtomicBoolean();
                EntityManager observed=(EntityManager)Proxy.newProxyInstance(EntityManager.class.getClassLoader(),new Class<?>[]{EntityManager.class},(proxy,method,args) -> {
                    Object value=invoke(method,em,args);
                    if(method.getName().equals("createQuery") && args[0].toString().startsWith("select count(f)")) {
                        TypedQuery<?> query=(TypedQuery<?>)value;
                        return Proxy.newProxyInstance(TypedQuery.class.getClassLoader(),new Class<?>[]{TypedQuery.class},(qp,qm,qa) -> {
                            Object result=invoke(qm,query,qa);
                            if(qm.getName().equals("getSingleResult") && paused.compareAndSet(false,true)) { counted.countDown(); await(committed); }
                            return result==query?qp:result;
                        });
                    }
                    return value;
                });
                return new AttachmentDatabaseService(observed).listForPatient(PATIENT,"appointment1","1","20");
            });
            writer.get(15,TimeUnit.SECONDS); assertEquals(1,page.getTotal()); assertEquals(1,page.getItems().size());
            AttachmentPage next=tx(em -> new AttachmentDatabaseService(em).listForPatient(PATIENT,"appointment1",null,null));
            assertEquals(2,next.getTotal()); assertEquals(2,next.getItems().size());
            System.out.println("SNAPSHOT_BARRIER count=1, concurrent publication committed, rows=1; next transaction count=2 rows=2");
        } finally { counted.countDown(); writer.cancel(true); worker.shutdownNow(); assertTrue(worker.awaitTermination(20,TimeUnit.SECONDS)); }
    }
}
