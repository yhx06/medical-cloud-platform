package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.order.PaymentOrder;
import org.junit.jupiter.api.Test;
import javax.persistence.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** MySQL 8, RESOURCE_LOCAL, REPEATABLE_READ and the real migrated schema; not JTA evidence. */
public class MySqlClinicalSupplementTest extends ClinicalSupplementFixture {
    private static final String PREFIX="COURSE_CLINICAL_MYSQL_";
    private static final Set<String> TABLES=new HashSet<>(Arrays.asList("tenant","department","user_account",
            "doctor_profile","schedule_slot","appointment","clinical_record","payment_order","audit_event",
            "file_metadata","clinical_draft","record_correction"));
    private static final Pattern URL=Pattern.compile("^jdbc:mysql://127\\.0\\.0\\.1:([0-9]{1,5})/(course_clinical_test_[a-f0-9]{32}|performance_schema)(?:\\?([^#]*))?$");
    private static final Set<String> OPTIONS=new HashSet<>(Arrays.asList("useSSL","allowPublicKeyRetrieval",
            "serverTimezone","characterEncoding","connectTimeout","socketTimeout"));
    private String url,user,password,uuid,database,observerUrl,observerUser,observerPassword;

    @Override protected Map<String,Object> settings() {
        String required=System.getenv(PREFIX+"REQUIRED");
        assertTrue(required==null||"true".equals(required)||"false".equals(required),"REQUIRED must be true or false");
        List<String> names=Arrays.asList("URL","USER","PASSWORD","UUID","OBSERVER_URL","OBSERVER_USER","OBSERVER_PASSWORD");
        boolean any=false;
        for(String name:names) if(System.getenv(PREFIX+name)!=null) any=true;
        if(!any && !"true".equals(required)) assumeTrue(false,"OPTIONAL: explicitly owned clinical MySQL fixture absent; SQL unverified");
        for(String name:names) assertTrue(System.getenv(PREFIX+name)!=null&&!System.getenv(PREFIX+name).isEmpty(),"Required clinical MySQL fixture variable missing: "+PREFIX+name);
        url=System.getenv(PREFIX+"URL"); user=System.getenv(PREFIX+"USER"); password=System.getenv(PREFIX+"PASSWORD"); uuid=System.getenv(PREFIX+"UUID");
        observerUrl=System.getenv(PREFIX+"OBSERVER_URL"); observerUser=System.getenv(PREFIX+"OBSERVER_USER"); observerPassword=System.getenv(PREFIX+"OBSERVER_PASSWORD");
        Matcher writer=guardUrl(url,false),observer=guardUrl(observerUrl,true);
        database=writer.group(2);
        assertEquals(writer.group(1),observer.group(1),"Writer and observer must use the same owned instance");
        assertTrue(uuid.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"),"Expected live server UUID required");
        for(String account:Arrays.asList(user,observerUser)) assertTrue(account.matches("[A-Za-z0-9_]+")&&!"root".equalsIgnoreCase(account),"Explicit non-root account required");
        assertNotEquals(user,observerUser,"Observer must be a separate read-only account");
        try(Connection writerConnection=DriverManager.getConnection(url,user,password);
            Connection observerConnection=DriverManager.getConnection(observerUrl,observerUser,observerPassword)) {
            guardIdentity(writerConnection,false); guardIdentity(observerConnection,true);
            guardGrants(writerConnection,false); guardGrants(observerConnection,true);
            try(Statement query=observerConnection.createStatement(); ResultSet rows=query.executeQuery("select count(*) from performance_schema.data_lock_waits w join performance_schema.threads t on t.THREAD_ID=w.REQUESTING_THREAD_ID")) {
                assertTrue(rows.next(),"Observer must be able to read actual lock waits");
            }
        } catch(SQLException ex) { throw new AssertionError("Owned MySQL fixture preflight failed",ex); }
        Map<String,Object> map=new HashMap<>();
        map.put("javax.persistence.jdbc.driver","com.mysql.cj.jdbc.Driver"); map.put("javax.persistence.jdbc.url",url);
        map.put("javax.persistence.jdbc.user",user); map.put("javax.persistence.jdbc.password",password);
        map.put("hibernate.dialect","org.hibernate.dialect.MySQL5InnoDBDialect");
        map.put("hibernate.connection.isolation","4"); map.put("hibernate.hbm2ddl.auto","validate");
        map.put("hibernate.show_sql","false"); return map;
    }

    private static Matcher guardUrl(String value,boolean observer) {
        Matcher match=URL.matcher(value); assertTrue(match.matches(),"Only unique loopback clinical fixture URLs are allowed");
        int port=Integer.parseInt(match.group(1));
        assertTrue(port>0&&port<=65535&&!Arrays.asList(3318,8180,8188,10090).contains(port),"Protected or invalid fixture port");
        assertEquals(observer,"performance_schema".equals(match.group(2)),"Incorrect writer/observer database");
        if(match.group(3)!=null) {
            Set<String> seen=new HashSet<>();
            for(String option:match.group(3).split("&",-1)) {
                String[] pair=option.split("=",2);
                assertTrue(pair.length==2&&OPTIONS.contains(pair[0])&&seen.add(pair[0])&&!pair[1].isEmpty(),"Unsupported or duplicate JDBC option");
            }
        }
        return match;
    }

    private void guardIdentity(Connection connection,boolean observer) throws SQLException {
        try(Statement query=connection.createStatement(); ResultSet row=query.executeQuery("select @@server_uuid, database(), current_user(), @@version")) {
            assertTrue(row.next()); assertEquals(uuid.toLowerCase(Locale.ROOT),row.getString(1).toLowerCase(Locale.ROOT),"Live server UUID must match owned fixture");
            assertEquals(observer?"performance_schema":database,row.getString(2),"Live schema must match owned fixture");
            assertEquals(observer?observerUser:user,row.getString(3).split("@",2)[0],"Authenticated account must match explicit non-root account");
            assertTrue(row.getString(4).startsWith("8."),"Real MySQL 8 required");
        }
    }

    private void guardGrants(Connection connection,boolean observer) throws SQLException {
        Set<String> granted=new HashSet<>();
        Pattern tableGrant=Pattern.compile("^GRANT ([A-Z, ]+) ON `([^`]+)`\\.`([^`]+)` TO .+$");
        try(Statement query=connection.createStatement(); ResultSet rows=query.executeQuery("SHOW GRANTS")) {
            while(rows.next()) {
                String grant=rows.getString(1);
                if(grant.startsWith("GRANT USAGE ON *.* TO ")&&!grant.contains("WITH GRANT OPTION")) continue;
                Matcher match=tableGrant.matcher(grant);
                assertTrue(match.matches()&&!grant.contains("WITH GRANT OPTION"),"Only explicit per-table fixture privileges are allowed");
                assertEquals(observer?"performance_schema":database,match.group(2),"Cross-schema privilege refused");
                String table=match.group(3);
                assertTrue(observer?Arrays.asList("data_lock_waits","threads").contains(table):TABLES.contains(table),"Unexpected granted table");
                Set<String> privileges=new HashSet<>(Arrays.asList(match.group(1).split(", ")));
                assertEquals(observer?Collections.singleton("SELECT"):new HashSet<>(Arrays.asList("SELECT","INSERT","UPDATE","DELETE")),privileges,"Only narrowly required privileges are allowed");
                granted.add(table);
            }
        }
        assertEquals(observer?new HashSet<>(Arrays.asList("data_lock_waits","threads")):TABLES,granted,"Every required table must have explicit privileges");
    }

    @Override protected void beforeSeed() {
        tx(em -> {
            em.unwrap(org.hibernate.Session.class).doWork(connection -> {
                Set<String> actual=new HashSet<>();
                try(PreparedStatement query=connection.prepareStatement("select table_name, engine from information_schema.tables where table_schema=?")) {
                    query.setString(1,database);
                    try(ResultSet rows=query.executeQuery()) { while(rows.next()) { actual.add(rows.getString(1)); assertEquals("InnoDB",rows.getString(2),"Every fixture table must use InnoDB"); } }
                }
                assertEquals(TABLES,actual,"Full reviewed twelve-table schema required before any seed write");
            });
            for(String entity:Arrays.asList("AuditEvent","FileMetadata","RecordCorrection","ClinicalDraft","PaymentOrder","ClinicalRecord","Appointment","ScheduleSlot","DoctorProfile","Department","Account","Tenant")) em.createQuery("delete from "+entity).executeUpdate();
            return null;
        });
    }

    @Override <T>T tx(Function<EntityManager,T> work) {
        return super.tx(em -> {
            em.unwrap(org.hibernate.Session.class).doWork(connection -> {
                guardIdentity(connection,false);
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation(),"Real transactions must use REPEATABLE_READ");
            });
            return work.apply(em);
        });
    }

    @Test void fullNewSchemaJpaValidated() {
        assertTrue(factory.isOpen());
        assertEquals(12,factory.getMetamodel().getEntities().size());
        assertEquals(0,count("ClinicalDraft")); assertEquals(0,count("RecordCorrection"));
    }

    // Removing expected-revision checking would permit the second tab to overwrite the first.
    @Test void twoTabsOneRevisionWins() throws Exception {
        start();
        ordered("two draft tabs",em -> new ClinicalSupplementService(em).saveDraft(DOCTOR,"appointment1",draft(0,"first"),NOW,context(AuditEventType.CONSULTATION_DRAFT_SAVE)),
                em -> new ClinicalSupplementService(em).saveDraft(DOCTOR,"appointment1",draft(0,"second"),NOW,context(AuditEventType.CONSULTATION_DRAFT_SAVE)),"CLINICAL_REVISION_CONFLICT");
        ClinicalWorkspaceView workspace=tx(em -> new ClinicalSupplementService(em).workspace(DOCTOR,"appointment1"));
        assertEquals(1,workspace.getCurrentRevision()); assertEquals("first",workspace.getDraft().getContent().getDiagnosis());
        assertEquals(1,count("ClinicalDraft")); assertEquals(0,count("PaymentOrder")); assertEquals(2,count("AuditEvent"));
    }

    // Removing the post-lock state check would resurrect a draft after completion deletes it.
    @Test void completeVsDraftNoResurrection() throws Exception {
        start(); save(0,"draft");
        ordered("complete before draft",this::finishMatchingDraft,
                em -> new ClinicalSupplementService(em).saveDraft(DOCTOR,"appointment1",draft(1,"late"),NOW,context(AuditEventType.CONSULTATION_DRAFT_SAVE)),"CLINICAL_STATE_CONFLICT");
        assertCompleted(); assertEquals(0,count("ClinicalDraft")); assertEquals(3,count("AuditEvent"));
    }

    // Removing state rechecking under the parent lock would allow a late patient edit.
    @Test void completeVsDescriptionStateRechecked() throws Exception {
        start(); save(0,"draft");
        ordered("complete before description",this::finishMatchingDraft,
                em -> new ClinicalSupplementService(em).updateComplaint(PATIENT,"appointment1",complaint(0,"late"),NOW,context(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE)),"CLINICAL_STATE_CONFLICT");
        assertCompleted();
        PatientComplaintView complaint=tx(em -> new ClinicalSupplementService(em).complaint(PATIENT,"appointment1"));
        assertNull(complaint.getDescription()); assertEquals(0,complaint.getRevision()); assertNull(complaint.getUpdatedAt()); assertFalse(complaint.isWritable()); assertEquals(3,count("AuditEvent"));
    }

    // Removing correction serialization/revision checking would accept two sequence-one writes.
    @Test void twoCorrectionsOneSequence() throws Exception {
        String record=complete(); List<Object> original=originalAndOrder();
        ordered("two corrections",em -> new ClinicalSupplementService(em).appendCorrection(DOCTOR,record,correction(0,UUID.randomUUID().toString(),"first"),NOW,context(AuditEventType.RECORD_CORRECTION_APPEND)),
                em -> new ClinicalSupplementService(em).appendCorrection(DOCTOR,record,correction(0,UUID.randomUUID().toString(),"second"),NOW,context(AuditEventType.RECORD_CORRECTION_APPEND)),"CLINICAL_REVISION_CONFLICT");
        ClinicalRecordDetailsView details=tx(em -> new ClinicalSupplementService(em).doctorDetails(DOCTOR,record));
        assertEquals(1,details.getCurrentRevision()); assertEquals("first",details.getEffective().getDiagnosis());
        assertEquals(1,count("RecordCorrection")); assertEquals(3,count("AuditEvent")); assertEquals(original,originalAndOrder());
    }

    // Looking at the latest revision before operation-key recovery would reject a lost-response retry.
    @Test void sameOperationKeyRecoversAfterLaterCorrection() {
        String record=complete(); List<Object> original=originalAndOrder();
        AppendCorrectionRequest request=correction(0,UUID.randomUUID().toString(),"first");
        RecordCorrectionView first=append(record,request); append(record,correction(1,UUID.randomUUID().toString(),"second"));
        RecordCorrectionView recovered=append(record,request);
        assertEquals(first.getId(),recovered.getId()); assertEquals(1,recovered.getSequence()); assertEquals("first",recovered.getAfter().getDiagnosis());
        assertEquals(2,count("RecordCorrection"));
        assertEquals(1L,(long)tx(em -> em.createQuery("select count(c) from RecordCorrection c where c.operationKey=:key",Long.class).setParameter("key",request.getOperationKey()).getSingleResult()));
        assertEquals("second",tx(em -> new ClinicalSupplementService(em).doctorDetails(DOCTOR,record).getEffective().getDiagnosis()));
        assertEquals(1L,(long)tx(em -> em.createQuery("select count(e) from AuditEvent e where e.result=:result",Long.class).setParameter("result",AuditResult.NO_CHANGE).getSingleResult()));
        assertEquals(original,originalAndOrder());
    }

    // A separate audit commit or draft deletion outside the transaction would leave partial completion.
    @Test void completionAuditFailureRollsBackDraftDeletionRecordAndOrder() {
        start(); save(0,"draft"); long audits=count("AuditEvent");
        List<Object> draftBefore=draftSnapshot();
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> finishMatchingDraft(failAtAuditAfterBusinessFlush(em))));
        assertEquals("IN_PROGRESS",tx(em -> em.find(Appointment.class,"appointment1").getStatus()));
        assertEquals(draftBefore,draftSnapshot()); assertEquals(0,count("ClinicalRecord")); assertEquals(0,count("PaymentOrder")); assertEquals(audits,count("AuditEvent"));
        tx(this::finishMatchingDraft); assertCompleted(); assertEquals(0,count("ClinicalDraft"));
    }

    // Committing a correction despite audit failure would change effective content and consume a key.
    @Test void correctionAuditFailureLeavesOriginalOrderAndRevisionUnchanged() {
        String record=complete(); List<Object> original=originalAndOrder(); long audits=count("AuditEvent");
        AppendCorrectionRequest request=correction(0,UUID.randomUUID().toString(),"corrected");
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> new ClinicalSupplementService(failAtAuditAfterBusinessFlush(em)).appendCorrection(DOCTOR,record,request,NOW,context(AuditEventType.RECORD_CORRECTION_APPEND))));
        assertEquals(0,count("RecordCorrection")); assertEquals(audits,count("AuditEvent")); assertEquals(original,originalAndOrder());
        assertEquals(0,tx(em -> new ClinicalSupplementService(em).doctorDetails(DOCTOR,record)).getCurrentRevision());
        assertEquals(1,append(record,request).getSequence()); assertEquals(original,originalAndOrder());
    }

    // Committing dirty entities before failed audit would leak a complaint or draft revision.
    @Test void complaintAndDraftAuditFailuresRollBackContentAndRevision() {
        start(); save(0,"draft"); List<Object> before=draftSnapshot(); long audits=count("AuditEvent");
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> new ClinicalSupplementService(failAtAuditAfterBusinessFlush(em)).updateComplaint(PATIENT,"appointment1",complaint(0,"changed"),NOW,context(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE))));
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> new ClinicalSupplementService(failAtAuditAfterBusinessFlush(em)).saveDraft(DOCTOR,"appointment1",draft(1,"changed"),NOW,context(AuditEventType.CONSULTATION_DRAFT_SAVE))));
        PatientComplaintView complaint=tx(em -> new ClinicalSupplementService(em).complaint(PATIENT,"appointment1"));
        assertNull(complaint.getDescription()); assertEquals(0,complaint.getRevision()); assertNull(complaint.getUpdatedAt());
        assertEquals(before,draftSnapshot()); assertEquals(audits,count("AuditEvent")); assertEquals(0,count("PaymentOrder"));
    }

    private ClinicalRecord finishMatchingDraft(EntityManager em) {
        return new ClinicalService(em).complete(DOCTOR,"appointment1","final complaint","final diagnosis","final advice","final notes",NOW,context(AuditEventType.CONSULTATION_COMPLETE),1L);
    }
    private void assertCompleted() {
        assertEquals("COMPLETED",tx(em -> em.find(Appointment.class,"appointment1").getStatus()));
        assertEquals(1,count("ClinicalRecord")); assertEquals(1,count("PaymentOrder"));
    }
    private List<Object> draftSnapshot() {
        return tx(em -> { ClinicalDraft d=em.createQuery("select d from ClinicalDraft d",ClinicalDraft.class).getSingleResult(); ClinicalContentView c=d.content();
            return Arrays.asList(d.getId(),d.getAppointmentId(),d.getTenantId(),d.getDoctorId(),d.getPatientId(),d.getSavedAt(),d.getRevision(),c.getComplaint(),c.getDiagnosis(),c.getAdvice(),c.getNotes()); });
    }
    private List<Object> originalAndOrder() {
        return tx(em -> { ClinicalRecord r=em.createQuery("select r from ClinicalRecord r",ClinicalRecord.class).getSingleResult(); PaymentOrder o=em.createQuery("select o from PaymentOrder o",PaymentOrder.class).getSingleResult();
            return Arrays.asList(r.getId(),r.getAppointmentId(),r.getTenantId(),r.getDoctorId(),r.getPatientId(),r.getComplaint(),r.getDiagnosis(),r.getAdvice(),r.getNotes(),r.getCompletedAt(),o.getId(),o.getAppointmentId(),o.getPatientId(),o.getTenantId(),o.getAmount(),o.getStatus(),o.getPaidAt()); });
    }
    private static EntityManager failAtAuditAfterBusinessFlush(EntityManager em) {
        return (EntityManager)Proxy.newProxyInstance(EntityManager.class.getClassLoader(),new Class<?>[]{EntityManager.class},(proxy,method,args) -> {
            if(method.getName().equals("persist")&&args[0] instanceof AuditEvent) { em.flush(); throw new PersistenceException("Injected audit failure after real business SQL flush"); }
            try { return method.invoke(em,args); } catch(InvocationTargetException ex) { throw ex.getCause(); }
        });
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15,TimeUnit.SECONDS),"Transaction barrier must be reached"); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
    private long connectionId(EntityManager em) { return ((Number)em.createNativeQuery("select connection_id()").getSingleResult()).longValue(); }
    private void ordered(String label,Function<EntityManager,?> first,Function<EntityManager,?> second,String rejectedCode) throws Exception {
        CountDownLatch holding=new CountDownLatch(1),release=new CountDownLatch(1),connected=new CountDownLatch(1);
        AtomicLong holderId=new AtomicLong(),contenderId=new AtomicLong(); ExecutorService workers=Executors.newFixedThreadPool(2);
        Future<?> one=workers.submit(() -> tx(em -> { holderId.set(connectionId(em)); Object value=first.apply(em); em.flush(); holding.countDown(); await(release); return value; }));
        Future<?> two=null;
        try {
            await(holding);
            two=workers.submit(() -> tx(em -> { contenderId.set(connectionId(em)); connected.countDown(); return second.apply(em); }));
            await(connected); boolean waiting=false;
            try(Connection observer=DriverManager.getConnection(observerUrl,observerUser,observerPassword)) {
                guardIdentity(observer,true); guardGrants(observer,true);
                try(PreparedStatement query=observer.prepareStatement("select count(*) from performance_schema.data_lock_waits w join performance_schema.threads requester on requester.THREAD_ID=w.REQUESTING_THREAD_ID join performance_schema.threads blocker on blocker.THREAD_ID=w.BLOCKING_THREAD_ID where requester.PROCESSLIST_ID=? and blocker.PROCESSLIST_ID=?")) {
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(System.nanoTime()<deadline) {
                        query.setLong(1,contenderId.get()); query.setLong(2,holderId.get());
                        try(ResultSet rows=query.executeQuery()) { assertTrue(rows.next()); if(rows.getLong(1)>0) { waiting=true; break; } }
                        if(two.isDone()||one.isDone()) break;
                        Thread.yield();
                    }
                }
            }
            assertTrue(waiting,label+": actual contender-to-holder InnoDB lock wait required before commit");
            System.out.println("CLINICAL_LOCK_BARRIER "+label+": actual wait observed; releasing first commit");
            release.countDown(); one.get(15,TimeUnit.SECONDS);
            final Future<?> contender=two;
            ExecutionException failure=assertThrows(ExecutionException.class,() -> contender.get(15,TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ClinicalSupplementException,"Expected clinical conflict after first commit");
            assertEquals(rejectedCode,((ClinicalSupplementException)failure.getCause()).getCode());
        } finally {
            release.countDown(); one.cancel(true); if(two!=null) two.cancel(true); workers.shutdownNow();
            assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS),"Transaction workers must stop");
        }
    }
}
