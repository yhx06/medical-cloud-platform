package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
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

abstract class ClinicalSupplementFixture {
    static final Instant NOW=Instant.parse("2030-01-01T00:00:00Z");
    static final CurrentActor PATIENT=new CurrentActor("patient",Role.PATIENT,null);
    static final CurrentActor DOCTOR=new CurrentActor("doctor",Role.DOCTOR,"tenant");
    EntityManagerFactory factory;
    @BeforeEach void open() {
        factory=Persistence.createEntityManagerFactory("medicalCloudCourseTest",settings());
        beforeSeed();
        tx(em -> {
            em.persist(new Tenant("tenant","Demo"));
            for(String id:Arrays.asList("doctor","doctor2")) em.persist(new Account(id,id,"unused",Role.DOCTOR,"tenant"));
            for(String id:Arrays.asList("patient","other")) em.persist(new Account(id,id,"unused",Role.PATIENT,null));
            em.persist(new Account("admin","admin","unused",Role.HOSPITAL_ADMIN,"tenant"));
            em.persist(new Department("department","tenant","Demo"));
            em.persist(new DoctorProfile("doctor","tenant","department","Demo"));
            for(int i=1;i<=2;i++) {
                em.persist(new ScheduleSlot("slot"+i,"tenant","department","doctor",Date.valueOf("2030-01-02"),Time.valueOf(i==1?"09:00:00":"10:00:00"),Time.valueOf(i==1?"10:00:00":"11:00:00"),10,BigDecimal.TEN));
                em.persist(new Appointment("appointment"+i,"patient","slot"+i,"tenant","doctor",BigDecimal.TEN,Date.valueOf("2030-01-01"),NOW));
            } return null;
        });
    }
    protected Map<String,Object> settings() {
        Map<String,Object> settings=new HashMap<>();
        settings.put("javax.persistence.jdbc.url","jdbc:h2:mem:clinical_"+UUID.randomUUID()+";MODE=MySQL;LOCK_TIMEOUT=10000");
        return settings;
    }
    protected void beforeSeed() { }
    @AfterEach void close() { if(factory!=null) factory.close(); }
    <T>T tx(Function<EntityManager,T> work) {
        EntityManager em=factory.createEntityManager();
        try { em.getTransaction().begin(); T value=work.apply(em); em.getTransaction().commit(); return value; }
        finally { if(em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }
    }
    static AuditContext context(AuditEventType type) { return AuditContext.create(type,UUID.randomUUID().toString()); }
    static SupplementComplaintRequest complaint(long version,String text) { SupplementComplaintRequest r=new SupplementComplaintRequest();r.setExpectedRevision(version);r.setDescription(text);return r; }
    static SaveClinicalDraftRequest draft(long version,String diagnosis) { SaveClinicalDraftRequest r=new SaveClinicalDraftRequest();r.setExpectedRevision(version);r.setDiagnosis(diagnosis);r.setComplaint("draft complaint");r.setAdvice("draft advice");r.setNotes("draft notes");return r; }
    static AppendCorrectionRequest correction(long version,String key,String diagnosis) {
        AppendCorrectionRequest r=new AppendCorrectionRequest();r.setExpectedRevision(version);r.setOperationKey(key);r.setReason("reason");
        ClinicalContentRequest c=new ClinicalContentRequest();c.setDiagnosis(diagnosis);c.setAdvice("new advice");r.setAfter(c);return r;
    }
    void start() { tx(em -> new ClinicalService(em).start(DOCTOR,"appointment1",NOW)); }
    String complete() { start();return tx(em -> new ClinicalService(em).complete(DOCTOR,"appointment1","original complaint","original diagnosis","original advice","original notes",NOW).getId()); }
    long count(String entity) { return tx(em -> em.createQuery("select count(x) from "+entity+" x",Long.class).getSingleResult()); }
    static void code(String expected,Runnable action) { assertEquals(expected,assertThrows(ClinicalSupplementException.class,action::run).getCode()); }
    ClinicalDraftView save(long revision,String text) { return tx(em -> new ClinicalSupplementService(em).saveDraft(DOCTOR,"appointment1",draft(revision,text),NOW,context(AuditEventType.CONSULTATION_DRAFT_SAVE))); }
    RecordCorrectionView append(String record,AppendCorrectionRequest request) { return tx(em -> new ClinicalSupplementService(em).appendCorrection(DOCTOR,record,request,NOW,context(AuditEventType.RECORD_CORRECTION_APPEND))); }
}

public class ClinicalSupplementDatabaseTest extends ClinicalSupplementFixture {
    @Test void patientDescriptionTimestampAndNoChange() {
        PatientComplaintView first=tx(em -> new ClinicalSupplementService(em).updateComplaint(PATIENT,"appointment1",complaint(0," first "),NOW,context(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE)));
        assertEquals("first",first.getDescription());assertEquals(1,first.getRevision());assertEquals("2030-01-01T08:00:00+08:00",first.getUpdatedAt());
        PatientComplaintView same=tx(em -> new ClinicalSupplementService(em).updateComplaint(PATIENT,"appointment1",complaint(1,"first"),NOW.plusSeconds(20),context(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE)));
        assertEquals(first.getUpdatedAt(),same.getUpdatedAt());assertEquals(1,same.getRevision());
        code("CLINICAL_REVISION_CONFLICT",() -> tx(em -> new ClinicalSupplementService(em).updateComplaint(PATIENT,"appointment1",complaint(0,"first"),context(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE))));
        PatientComplaintView cleared=tx(em -> new ClinicalSupplementService(em).updateComplaint(PATIENT,"appointment1",complaint(1," "),context(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE)));
        assertNull(cleared.getDescription());assertEquals(2,cleared.getRevision());
        assertEquals(1L,(long)tx(em -> em.createQuery("select count(e) from AuditEvent e where e.result=:r",Long.class).setParameter("r",AuditResult.NO_CHANGE).getSingleResult()));
    }
    @Test void completedCanceledRejectPatientUpdate() {
        complete();tx(em -> {em.find(Appointment.class,"appointment2").cancel();return null;});
        for(String id:Arrays.asList("appointment1","appointment2")) code("CLINICAL_STATE_CONFLICT",() -> tx(em -> new ClinicalSupplementService(em).updateComplaint(PATIENT,id,complaint(0,"text"),context(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE))));
    }
    @Test void draftPrivateAndNoOrder() {
        start();assertEquals(0,count("PaymentOrder"));save(0,"draft diagnosis");assertEquals(0,count("PaymentOrder"));
        for(CurrentActor actor:Arrays.asList(PATIENT,new CurrentActor("admin",Role.HOSPITAL_ADMIN,"tenant"),new CurrentActor("doctor2",Role.DOCTOR,"tenant"))) assertThrows(SecurityException.class,() -> tx(em -> new ClinicalSupplementService(em).workspace(actor,"appointment1")));
    }
    @Test void saveThenReopenDraft() {
        start();ClinicalDraftView first=save(0,"draft diagnosis");
        ClinicalWorkspaceView reopened=tx(em -> new ClinicalSupplementService(em).workspace(DOCTOR,"appointment1"));
        assertEquals(1,reopened.getCurrentRevision());assertEquals("draft diagnosis",reopened.getDraft().getContent().getDiagnosis());
        assertEquals(first.getSavedAt(),save(1,"draft diagnosis").getSavedAt());assertEquals(1,count("ClinicalDraft"));
    }
    @Test void legacyCompleteWithoutDraftStillWorks() { String id=complete();assertEquals("original diagnosis",tx(em -> new ClinicalSupplementService(em).patientDetails(PATIENT,"appointment1").getOriginal().getDiagnosis()));assertNotNull(id);assertEquals(1,count("PaymentOrder")); }
    @Test void draftRequiresMatchingVersionOnComplete() {
        start();save(0,"draft diagnosis");
        code("CLINICAL_REVISION_CONFLICT",() -> tx(em -> new ClinicalService(em).complete(DOCTOR,"appointment1",null,"final","advice",null,NOW)));
        code("CLINICAL_REVISION_CONFLICT",() -> tx(em -> new ClinicalService(em).complete(DOCTOR,"appointment1",null,"final","advice",null,NOW,context(AuditEventType.CONSULTATION_COMPLETE),0L)));
        assertEquals(0,count("PaymentOrder"));assertEquals(1,count("ClinicalDraft"));
    }
    @Test void completeDeletesDraftAtomically() {
        start();save(0,"draft diagnosis");
        ClinicalRecord r=tx(em -> new ClinicalService(em).complete(DOCTOR,"appointment1",null,"final","advice",null,NOW,context(AuditEventType.CONSULTATION_COMPLETE),1L));
        assertNull(r.getComplaint());assertNull(r.getNotes());assertEquals("final",r.getDiagnosis());assertEquals(0,count("ClinicalDraft"));assertEquals(1,count("PaymentOrder"));
    }
    @Test void correctionBeforeAfterChain() {
        String record=complete();RecordCorrectionView one=append(record,correction(0,UUID.randomUUID().toString(),"one"));RecordCorrectionView two=append(record,correction(1,UUID.randomUUID().toString(),"two"));
        assertEquals(1,one.getSequence());assertEquals("original diagnosis",one.getBefore().getDiagnosis());assertEquals("one",two.getBefore().getDiagnosis());assertEquals(2,two.getSequence());
        ClinicalRecordDetailsView details=tx(em -> new ClinicalSupplementService(em).doctorDetails(DOCTOR,record));assertEquals("original diagnosis",details.getOriginal().getDiagnosis());assertEquals("two",details.getEffective().getDiagnosis());assertEquals(2,details.getCurrentRevision());
    }
    @Test void sameKeyRecoversAfterLaterCorrection() {
        String record=complete();AppendCorrectionRequest request=correction(0,UUID.randomUUID().toString(),"one");RecordCorrectionView first=append(record,request);append(record,correction(1,UUID.randomUUID().toString(),"two"));
        assertEquals(first.getId(),append(record,request).getId());assertEquals(2,count("RecordCorrection"));
        assertEquals(1L,(long)tx(em -> em.createQuery("select count(c) from RecordCorrection c where c.operationKey=:key",Long.class).setParameter("key",request.getOperationKey()).getSingleResult()));
    }
    @Test void keyVariantConflict() {
        String record=complete(),key=UUID.randomUUID().toString();append(record,correction(0,key,"one"));
        code("CLINICAL_OPERATION_CONFLICT",() -> append(record,correction(0,key,"variant")));
        code("CLINICAL_OPERATION_CONFLICT",() -> append(record,correction(1,key,"one")));
        tx(em -> new ClinicalService(em).start(DOCTOR,"appointment2",NOW));String other=tx(em -> new ClinicalService(em).complete(DOCTOR,"appointment2","diag","advice").getId());
        code("CLINICAL_OPERATION_CONFLICT",() -> append(other,correction(0,key,"one")));
    }
    @Test void originalRecordOrderUnchanged() {
        String record=complete();String before=summary();append(record,correction(0,UUID.randomUUID().toString(),"corrected"));assertEquals(before,summary());
    }
    String summary() { return tx(em -> {ClinicalRecord r=em.createQuery("select r from ClinicalRecord r",ClinicalRecord.class).getSingleResult();com.medcloud.course.paas.order.PaymentOrder o=em.createQuery("select o from PaymentOrder o",com.medcloud.course.paas.order.PaymentOrder.class).getSingleResult();return Arrays.asList(r.getId(),r.getAppointmentId(),r.getTenantId(),r.getDoctorId(),r.getPatientId(),r.getComplaint(),r.getDiagnosis(),r.getAdvice(),r.getNotes(),r.getCompletedAt(),o.getId(),o.getAppointmentId(),o.getPatientId(),o.getTenantId(),o.getAmount(),o.getStatus(),o.getPaidAt()).toString();}); }
    @Test void disabledAccountAndFrozenHospital() {
        String record=complete();tx(em -> {em.find(Tenant.class,"tenant").setStatus("DISABLED");return null;});
        assertNotNull(tx(em -> new ClinicalSupplementService(em).patientDetails(PATIENT,"appointment1")));
        code("CLINICAL_STATE_CONFLICT",() -> append(record,correction(0,UUID.randomUUID().toString(),"new")));
        assertFalse((boolean)tx(em -> new ClinicalSupplementService(em).complaint(PATIENT,"appointment2").isWritable()));
        tx(em -> {em.find(Account.class,"patient").disable();return null;});assertThrows(SecurityException.class,() -> tx(em -> new ClinicalSupplementService(em).patientDetails(PATIENT,"appointment1")));
    }
    @Test void sameHospitalWrongDoctorDenied() {String record=complete();CurrentActor other=new CurrentActor("doctor2",Role.DOCTOR,"tenant");assertThrows(SecurityException.class,() -> tx(em -> new ClinicalSupplementService(em).doctorDetails(other,record)));assertThrows(SecurityException.class,() -> tx(em -> new ClinicalSupplementService(em).correctionsForDoctor(other,record,"bad","bad")));}
    @Test void historyPaginationAuthorizesFirstAndDefaultsToFive() {
        String record=complete();for(int i=0;i<6;i++) append(record,correction(i,UUID.randomUUID().toString(),"v"+i));
        RecordCorrectionPage p=tx(em -> new ClinicalSupplementService(em).correctionsForPatient(PATIENT,"appointment1",null,null));assertEquals(5,p.getPageSize());assertEquals(6,p.getTotal());assertEquals(1,p.getItems().get(0).getSequence());assertEquals(5,p.getItems().size());
        code("CLINICAL_INPUT_INVALID",() -> tx(em -> new ClinicalSupplementService(em).correctionsForDoctor(DOCTOR,record,"1","6")));
        assertThrows(SecurityException.class,() -> tx(em -> new ClinicalSupplementService(em).correctionsForPatient(new CurrentActor("other",Role.PATIENT,null),"appointment1","bad","bad")));
    }
}
