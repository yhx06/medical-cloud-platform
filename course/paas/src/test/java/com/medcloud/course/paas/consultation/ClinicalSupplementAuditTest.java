package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.appointment.Appointment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ClinicalSupplementAuditTest extends ClinicalSupplementFixture {
    AuditContext duplicate(AuditEventType type) {
        AuditContext c=context(type);tx(em -> {c.attachActor(DOCTOR);c.attachVerifiedTenant("tenant");new AuditJournal(em).append(c,"previous",AuditResult.SUCCESS,NOW);return null;});return c;
    }
    String businessSummary() {return tx(em -> em.createQuery("select a from Appointment a order by a.id",Appointment.class).getResultList().stream().map(a -> a.getId()+":"+a.getStatus()+":"+a.getPatientComplaint()+":"+a.getComplaintRevision()+":"+a.getComplaintUpdatedAt()).collect(java.util.stream.Collectors.joining("|")))+":"+count("ClinicalDraft")+":"+count("RecordCorrection")+":"+count("ClinicalRecord")+":"+count("PaymentOrder");}
    @Test void auditFailureRollsBackComplaintDraftAndCorrection() {
        AuditContext complaint=duplicate(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE);String before=businessSummary();
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> new ClinicalSupplementService(em).updateComplaint(PATIENT,"appointment1",complaint(0,"private text"),NOW,complaint)));assertEquals(before,businessSummary());
        start();AuditContext draft=duplicate(AuditEventType.CONSULTATION_DRAFT_SAVE);before=businessSummary();
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> new ClinicalSupplementService(em).saveDraft(DOCTOR,"appointment1",draft(0,"private draft"),NOW,draft)));assertEquals(before,businessSummary());
        String record=tx(em -> new ClinicalService(em).complete(DOCTOR,"appointment1","original","advice").getId());AuditContext correction=duplicate(AuditEventType.RECORD_CORRECTION_APPEND);before=businessSummary();
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> new ClinicalSupplementService(em).appendCorrection(DOCTOR,record,correction(0,java.util.UUID.randomUUID().toString(),"private correction"),NOW,correction)));assertEquals(before,businessSummary());
    }
    @Test void completeAuditFailureKeepsDraftAndState() {
        start();save(0,"draft");String before=businessSummary();AuditContext c=duplicate(AuditEventType.CONSULTATION_COMPLETE);
        assertThrows(AuditInfrastructureException.class,() -> tx(em -> new ClinicalService(em).complete(DOCTOR,"appointment1",null,"final","advice",null,NOW,c,1L)));assertEquals(before,businessSummary());
    }
}
