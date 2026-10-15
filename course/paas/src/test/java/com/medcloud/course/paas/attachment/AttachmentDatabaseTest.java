package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.consultation.*;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.tenant.*;
import org.junit.jupiter.api.*;
import javax.persistence.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

abstract class AttachmentFixture {
    static final Instant NOW=Instant.parse("2030-01-01T00:00:00Z");
    static final CurrentActor PATIENT=new CurrentActor("patient",Role.PATIENT,null);
    static final CurrentActor DOCTOR=new CurrentActor("doctor",Role.DOCTOR,"tenant");
    static final String SHA=String.join("",Collections.nCopies(64,"a"));
    protected EntityManagerFactory factory;
    protected Map<String,Object> settings() {
        Map<String,Object> map=new HashMap<>();
        map.put("javax.persistence.jdbc.url","jdbc:h2:mem:attachment_"+UUID.randomUUID()+";MODE=MySQL");
        return map;
    }
    @BeforeEach void open() {
        factory=Persistence.createEntityManagerFactory("medicalCloudCourseTest",settings());
        beforeSeed();
        tx(em -> {
            em.persist(new Tenant("tenant","Demo hospital"));
            em.persist(new Account("doctor","doctor","unused",Role.DOCTOR,"tenant"));
            em.persist(new Account("patient","patient","unused",Role.PATIENT,null));
            em.persist(new Account("other","other","unused",Role.PATIENT,null));
            em.persist(new Account("admin","admin","unused",Role.HOSPITAL_ADMIN,"tenant"));
            em.persist(new Department("department","tenant","Demo"));
            em.persist(new DoctorProfile("doctor","tenant","department","Demo doctor"));
            for(int i=1;i<=3;i++) {
                em.persist(new ScheduleSlot("slot"+i,"tenant","department","doctor",Date.valueOf("2030-01-02"),Time.valueOf(String.format("%02d:00:00",8+i)),Time.valueOf(String.format("%02d:00:00",9+i)),10,BigDecimal.TEN));
                em.persist(new Appointment("appointment"+i,"patient","slot"+i,"tenant","doctor",BigDecimal.TEN,Date.valueOf("2030-01-01"),NOW));
            }
            return null;
        });
    }
    @AfterEach void close() { if(factory!=null) factory.close(); }
    protected void beforeSeed() { }
    static PrepareUploadRequest request(String key) { return new PrepareUploadRequest(key,"demo.png","image/png",20,SHA); }
    static AuditContext context(AuditEventType event) { return AuditContext.create(event,UUID.randomUUID().toString()); }
    protected <T>T tx(Function<EntityManager,T> work) {
        EntityManager em=factory.createEntityManager();
        try { em.getTransaction().begin(); T result=work.apply(em); em.getTransaction().commit(); return result; }
        finally { if(em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }
    }
    protected String prepare(String appointment) { return tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT,appointment,request(UUID.randomUUID().toString()),NOW).getUploadId()); }
    protected UploadAttempt claim(String id) { return tx(em -> new AttachmentDatabaseService(em).claim(PATIENT,id,NOW,context(AuditEventType.FILE_UPLOAD))); }
    protected AttachmentView publish(String id,UploadAttempt attempt) { return tx(em -> new AttachmentDatabaseService(em).publish(PATIENT,id,attempt.getAttemptToken(),validated(),NOW,context(AuditEventType.FILE_UPLOAD))); }
    static ValidatedAttachment validated() { return new ValidatedAttachment(null,"demo.png","image/png",20,SHA); }
    static void code(String expected,Runnable operation) { assertEquals(expected,assertThrows(AttachmentException.class,operation::run).getCode()); }
}

public class AttachmentDatabaseTest extends AttachmentFixture {
    @Test void sameKeySameDescriptorReturnsSameIntent() {
        String key=UUID.randomUUID().toString();
        String id=tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment1",request(key),NOW).getUploadId());
        assertEquals(id,tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment1",request(key),NOW).getUploadId()));
        code("UPLOAD_CONFLICT",() -> tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment2",request(key),NOW)));
        code("UPLOAD_CONFLICT",() -> tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT,"appointment1",new PrepareUploadRequest(key,"other.png","image/png",20,SHA),NOW)));
        assertEquals(1L,(long)tx(em -> em.createQuery("select count(f) from FileMetadata f",Long.class).getSingleResult()));
    }
    @Test void patientInProgressCanPrepareAndPublish() {
        tx(em -> { em.find(Appointment.class,"appointment1").start(NOW); return null; });
        String id=prepare("appointment1"); assertEquals(id,publish(id,claim(id)).getFileId());
        assertEquals(1L,(long)tx(em -> new AttachmentDatabaseService(em).listForDoctor(DOCTOR,"appointment1","1","20").getTotal()));
    }
    @Test void doctorAppendRequiresCompletedOwnedRecord() {
        code("FORBIDDEN",() -> tx(em -> new AttachmentDatabaseService(em).prepareDoctor(DOCTOR,"missing",request(UUID.randomUUID().toString()),NOW)));
        String record=tx(em -> { ClinicalService clinical=new ClinicalService(em); clinical.start(DOCTOR,"appointment1",NOW); return clinical.complete(DOCTOR,"appointment1",null,"Demo","Demo",null,NOW).getId(); });
        String id=tx(em -> new AttachmentDatabaseService(em).prepareDoctor(DOCTOR,record,request(UUID.randomUUID().toString()),NOW).getUploadId());
        UploadAttempt attempt=tx(em -> new AttachmentDatabaseService(em).claim(DOCTOR,id,NOW,context(AuditEventType.FILE_UPLOAD)));
        tx(em -> new AttachmentDatabaseService(em).publish(DOCTOR,id,attempt.getAttemptToken(),validated(),NOW,context(AuditEventType.FILE_UPLOAD)));
        assertEquals(AttachmentSourceType.DOCTOR_RECORD,tx(em -> new AttachmentDatabaseService(em).listForPatient(PATIENT,"appointment1",null,null).getItems().get(0).getSourceType()));
        assertEquals(NOW,tx(em -> em.find(ClinicalRecord.class,record).getCompletedAt().toInstant()));
        assertEquals(1L,(long)tx(em -> em.createQuery("select count(o) from PaymentOrder o",Long.class).getSingleResult()));
    }
    @Test void disabledHospitalAllowsHistoryButRejectsWrites() {
        String id=prepare("appointment1"); publish(id,claim(id));
        tx(em -> { em.find(Tenant.class,"tenant").setStatus("DISABLED"); return null; });
        assertEquals(1L,(long)tx(em -> new AttachmentDatabaseService(em).listForPatient(PATIENT,"appointment1",null,null).getTotal()));
        assertNotNull(tx(em -> new AttachmentDatabaseService(em).downloadCandidate(PATIENT,id)));
        code("FILE_STATE_CONFLICT",() -> prepare("appointment2"));
        code("FILE_STATE_CONFLICT",() -> tx(em -> new AttachmentDatabaseService(em).logicalDelete(PATIENT,id,NOW,context(AuditEventType.FILE_DELETE))));
    }
    @Test void adminsAndWrongOwnerSeeNoMetadata() {
        String id=prepare("appointment1"); publish(id,claim(id));
        for(CurrentActor actor:Arrays.asList(new CurrentActor("admin",Role.HOSPITAL_ADMIN,"tenant"),new CurrentActor("other",Role.PATIENT,null))) {
            code("FORBIDDEN",() -> tx(em -> new AttachmentDatabaseService(em).downloadCandidate(actor,id)));
            code("FORBIDDEN",() -> tx(em -> new AttachmentDatabaseService(em).operation(actor,id)));
        }
    }
    @Test void staleAttemptCannotPublishAfterAbort() {
        String id=prepare("appointment1"); UploadAttempt attempt=claim(id);
        assertTrue((boolean)tx(em -> new AttachmentDatabaseService(em).abortAttempt(id,attempt.getAttemptToken(),NOW)));
        code("FILE_STATE_CONFLICT",() -> publish(id,attempt));
        assertEquals(AttachmentState.ABORTED,tx(em -> new AttachmentDatabaseService(em).operation(PATIENT,id).getState()));
    }
    @Test void publishAuditFailureRollsBackAvailable() {
        String id=prepare("appointment1"); UploadAttempt attempt=claim(id);
        AuditContext duplicate=context(AuditEventType.FILE_UPLOAD);
        tx(em -> { duplicate.attachActor(PATIENT); duplicate.attachVerifiedTenant("tenant"); new AuditJournal(em).append(duplicate,"previous",AuditResult.SUCCESS,NOW); return null; });
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> new AttachmentDatabaseService(em).publish(PATIENT,id,attempt.getAttemptToken(),validated(),NOW,duplicate)));
        assertEquals(AttachmentState.UPLOADING,tx(em -> new AttachmentDatabaseService(em).operation(PATIENT,id).getState()));
    }
    @Test void repeatedDeleteReturnsOriginalStateAndNoChange() {
        String id=prepare("appointment1"); publish(id,claim(id));
        assertEquals(AttachmentState.DELETE_PENDING,tx(em -> new AttachmentDatabaseService(em).logicalDelete(PATIENT,id,NOW,context(AuditEventType.FILE_DELETE)).getState()));
        tx(em -> { em.find(Appointment.class,"appointment1").start(NOW); return null; });
        assertEquals(AttachmentState.DELETE_PENDING,tx(em -> new AttachmentDatabaseService(em).logicalDelete(PATIENT,id,NOW.plusSeconds(10),context(AuditEventType.FILE_DELETE)).getState()));
        assertEquals(1L,(long)tx(em -> em.createQuery("select count(e) from AuditEvent e where e.result=:result",Long.class).setParameter("result",AuditResult.NO_CHANGE).getSingleResult()));
    }
    @Test void authorizationBeforeBadPagination() {
        code("FORBIDDEN",() -> tx(em -> new AttachmentDatabaseService(em).listForPatient(new CurrentActor("other",Role.PATIENT,null),"appointment1","bad","bad")));
        assertThrows(IllegalArgumentException.class,() -> tx(em -> new AttachmentDatabaseService(em).listForPatient(PATIENT,"appointment1","bad","bad")));
    }
    @Test void quotaAndLeasesAndCleanupPreserveTombstones() {
        String id=prepare("appointment1"); prepare("appointment2"); code("FILE_LIMIT_REACHED",() -> prepare("appointment3"));
        List<CleanupCandidate> batch=tx(em -> new AttachmentDatabaseService(em).claimCleanupBatch(NOW.plusSeconds(901),100));
        assertEquals(2,batch.size());
        for(CleanupCandidate c:batch) tx(em -> { new AttachmentDatabaseService(em).recordCleanup(c.getFileId(),c.getVersion(),true,NOW.plusSeconds(902)); return null; });
        assertEquals(AttachmentState.ABORTED,tx(em -> new AttachmentDatabaseService(em).operation(PATIENT,id).getState()));
        assertTrue(tx(em -> new AttachmentDatabaseService(em).claimCleanupBatch(NOW.plusSeconds(903),100)).isEmpty());
        assertEquals(2,tx(em -> new AttachmentDatabaseService(em).claimCleanupBatch(NOW.plusSeconds(2703),100)).size());
        prepare("appointment3");
    }
}
