package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.audit.*;
import javax.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ClinicalSupplementConcurrencyTest extends ClinicalSupplementFixture {
    // Independent persistence contexts race real writes; no shared EntityManager or guessed sleeps.
    List<Object> race(Function<EntityManager,Object> a,Function<EntityManager,Object> b) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try {
            List<Future<Object>> futures=new ArrayList<>();
            for(Function<EntityManager,Object> task:Arrays.asList(a,b)) futures.add(pool.submit(() -> {ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));try{return tx(task);}catch(ClinicalSupplementException ex){return ex.getCode();}}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();List<Object> results=new ArrayList<>();for(Future<Object> f:futures) results.add(f.get(20,TimeUnit.SECONDS));return results;
        } finally {pool.shutdownNow();}
    }
    @Test void twoTabsOneRevisionWins() throws Exception {
        start();List<Object> r=race(em -> new ClinicalSupplementService(em).saveDraft(DOCTOR,"appointment1",draft(0,"one"),context(AuditEventType.CONSULTATION_DRAFT_SAVE)),em -> new ClinicalSupplementService(em).saveDraft(DOCTOR,"appointment1",draft(0,"two"),context(AuditEventType.CONSULTATION_DRAFT_SAVE)));
        assertEquals(1,r.stream().filter(x -> x instanceof ClinicalDraftView).count());assertTrue(r.contains("CLINICAL_REVISION_CONFLICT"));assertEquals(1,count("ClinicalDraft"));
    }
    @Test void completeVsDraftNoResurrection() throws Exception {
        start();List<Object> r=race(em -> new ClinicalService(em).complete(DOCTOR,"appointment1",null,"final","advice",null,NOW),em -> new ClinicalSupplementService(em).saveDraft(DOCTOR,"appointment1",draft(0,"draft"),context(AuditEventType.CONSULTATION_DRAFT_SAVE)));
        String state=tx(em -> em.find(Appointment.class,"appointment1").getStatus());
        if("COMPLETED".equals(state)){assertEquals(0,count("ClinicalDraft"));assertEquals(1,count("PaymentOrder"));assertTrue(r.contains("CLINICAL_STATE_CONFLICT"));}
        else {assertEquals("IN_PROGRESS",state);assertEquals(1,count("ClinicalDraft"));assertEquals(0,count("PaymentOrder"));assertTrue(r.contains("CLINICAL_REVISION_CONFLICT"));}
    }
    @Test void completeVsDescriptionStateRechecked() throws Exception {
        start();List<Object> r=race(em -> new ClinicalService(em).complete(DOCTOR,"appointment1",null,"final","advice",null,NOW),em -> new ClinicalSupplementService(em).updateComplaint(PATIENT,"appointment1",complaint(0,"patient text"),context(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE)));
        assertEquals("COMPLETED",tx(em -> em.find(Appointment.class,"appointment1").getStatus()));assertEquals(1,count("PaymentOrder"));
        long revision=tx(em -> new ClinicalSupplementService(em).complaint(PATIENT,"appointment1").getRevision());assertTrue(revision==0||revision==1);if(revision==0)assertTrue(r.contains("CLINICAL_STATE_CONFLICT"));
        assertNull(tx(em -> new ClinicalSupplementService(em).patientDetails(PATIENT,"appointment1").getOriginal().getComplaint()));
    }
    @Test void twoCorrectionsOneSequence() throws Exception {
        String record=complete();List<Object> r=race(em -> new ClinicalSupplementService(em).appendCorrection(DOCTOR,record,correction(0,UUID.randomUUID().toString(),"one"),context(AuditEventType.RECORD_CORRECTION_APPEND)),em -> new ClinicalSupplementService(em).appendCorrection(DOCTOR,record,correction(0,UUID.randomUUID().toString(),"two"),context(AuditEventType.RECORD_CORRECTION_APPEND)));
        assertEquals(1,r.stream().filter(x -> x instanceof RecordCorrectionView).count());assertTrue(r.contains("CLINICAL_REVISION_CONFLICT"));assertEquals(1,count("RecordCorrection"));
    }
}
