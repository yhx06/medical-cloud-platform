package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.appointment.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.consultation.*;
import com.medcloud.course.paas.order.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditClinicalFlowTest extends AuditClinicalFixture {
    // Missing writes, trusting claimed hospital, wrong object or duplicate completion must break this test.
    @ParameterizedTest
    @EnumSource(value=AuditEventType.class, names={"APPOINTMENT_BOOK","APPOINTMENT_CANCEL","CONSULTATION_START","CONSULTATION_COMPLETE","PAYMENT_PAY"})
    void bookCancelStartCompletePayHaveCorrectTenantAndObject(AuditEventType type) throws Exception {
        try (AuditLogCapture logs = new AuditLogCapture()) {
            String object = tx(() -> mutate(type, null));
            manager.clear();
            List<AuditEvent> rows = events(type);
            assertEquals(1, rows.size());
            AuditEvent e = rows.get(0);
            assertEquals(type, e.getEventType()); assertEquals(object, e.getObjectId());
            assertEquals(type == AuditEventType.CONSULTATION_COMPLETE ? AuditObjectType.CLINICAL_RECORD :
                    type == AuditEventType.PAYMENT_PAY ? AuditObjectType.PAYMENT_ORDER : AuditObjectType.APPOINTMENT, e.getObjectType());
            assertEquals("tenant-a", e.getTenantId());
            boolean doctor = type == AuditEventType.CONSULTATION_START || type == AuditEventType.CONSULTATION_COMPLETE;
            assertEquals(doctor ? "doctor-a" : "patient-a", e.getActorId());
            assertEquals(doctor ? Role.DOCTOR : Role.PATIENT, e.getActorRole());
            assertEquals(AuditResult.SUCCESS, e.getResult()); assertEquals(AuditReasonCode.OK, e.getReasonCode());
            assertNotNull(e.getRecordedAt()); UUID.fromString(e.getRequestId());
            if (type == AuditEventType.CONSULTATION_COMPLETE) {
                assertEquals("COMPLETED", manager.find(Appointment.class,"progress").getStatus());
                assertEquals(CLINICAL, manager.find(ClinicalRecord.class,object).getDiagnosis());
                assertEquals(1L, manager.createQuery("select count(o) from PaymentOrder o where o.appointmentId='progress'",Long.class).getSingleResult());
            }
            assertFalse(new ObjectMapper().writeValueAsString(rows).contains(CLINICAL));
            logs.assertAbsent(CLINICAL);
        }
    }

    @Test void completionWritesOneEventNotTwo() {
        String id = tx(() -> new ClinicalService(manager).complete(DOCTOR,"progress",CLINICAL,"fictional advice").getId());
        assertEquals(1, events(AuditEventType.CONSULTATION_COMPLETE).size());
        assertEquals(id, events(AuditEventType.CONSULTATION_COMPLETE).get(0).getObjectId());
        assertEquals(1L, manager.createQuery("select count(r) from ClinicalRecord r where r.appointmentId='progress'",Long.class).getSingleResult());
        assertEquals(1L, manager.createQuery("select count(o) from PaymentOrder o where o.appointmentId='progress'",Long.class).getSingleResult());
    }

    @ParameterizedTest @EnumSource(value=AuditEventType.class,names={"CONSULTATION_START","CONSULTATION_COMPLETE"})
    void convenienceContextOverloadsKeepRequestIdAndWriteExactlyOnce(AuditEventType type) {
        AuditContext c=AuditContext.create(type,UUID.randomUUID().toString());
        String id=tx(() -> type==AuditEventType.CONSULTATION_START ? new ClinicalService(manager).start(DOCTOR,"booked",c).getId() :
                new ClinicalService(manager).complete(DOCTOR,"progress",CLINICAL,"fictional advice",c).getId());
        List<AuditEvent> rows=events(type); assertEquals(1,rows.size());
        assertEquals(c.getRequestId(),rows.get(0).getRequestId()); assertEquals(id,rows.get(0).getObjectId());
    }

    @Test void repeatPaymentIsNoChangeWithOriginalPaidAt() {
        PaymentService payments = new PaymentService(manager);
        java.sql.Timestamp firstPaidAt = tx(() -> payments.pay(PATIENT,"order-unpaid").getPaidAt());
        tx(() -> payments.pay(PATIENT,"order-unpaid")); manager.clear();
        List<AuditEvent> rows = events(AuditEventType.PAYMENT_PAY);
        assertEquals(2, rows.size());
        AuditEvent secondPaymentEvent = rows.get(1);
        assertEquals(AuditResult.SUCCESS, rows.get(0).getResult());
        assertEquals(AuditResult.NO_CHANGE, secondPaymentEvent.getResult());
        assertEquals(AuditReasonCode.UNCHANGED, secondPaymentEvent.getReasonCode());
        PaymentOrder orderAfterRetry = manager.find(PaymentOrder.class,"order-unpaid");
        assertEquals(firstPaidAt, orderAfterRetry.getPaidAt());
        long ordersForAppointment = manager.createQuery("select count(o) from PaymentOrder o where o.appointmentId='unpaid'",Long.class).getSingleResult();
        assertEquals(1L, ordersForAppointment);
    }

    @Test void repeatCompleteStillConflictsWithoutExtraRows() {
        tx(() -> new ClinicalService(manager).complete(DOCTOR,"progress",CLINICAL,"fictional advice"));
        List<String> before = snapshot();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run(AuditEventType.CONSULTATION_COMPLETE,
                DOCTOR, c -> new ClinicalService(manager).complete(DOCTOR,"progress",null,"overwrite","overwrite",null,BEFORE,c)));
        assertEquals(409, new com.medcloud.course.paas.api.ApiFailureMapper().toResponse(failure).getStatus());
        manager.clear(); assertEquals(before,snapshot());
        List<AuditEvent> rows = events(AuditEventType.CONSULTATION_COMPLETE);
        assertEquals(2, rows.size()); assertEquals(AuditResult.FAILED,rows.get(1).getResult());
        assertNull(rows.get(1).getObjectId());
    }
}

// Explicit RESOURCE_LOCAL business boundary; the independent writer asserts it has ended.
abstract class AuditClinicalFixture extends TenantFixture {
    static final String CLINICAL = "fictional_clinical_source_sentinel";
    // Older direct-resource assertions inspect original exception classes. Keep those assertions,
    // while exercising the actual runner and ending the failed fixture transaction before its writer.
    static AuditOperationRunner legacyRunner(javax.persistence.EntityManager em) {
        AuditOperationRunner real=AuditLocalTransactions.runner(em);
        return new AuditOperationRunner() {
            @Override public <T> T execute(AuditContext context, AuditAction<T> action) {
                boolean callerTransaction=em.getTransaction().isActive();
                if(!callerTransaction) em.getTransaction().begin();
                try {
                    return real.execute(context,c -> {
                        try { T result=action.execute(c); if(!callerTransaction) em.getTransaction().commit(); return result; }
                        catch(RuntimeException failure) {
                            if(em.getTransaction().isActive()) em.getTransaction().rollback();
                            em.clear(); throw failure;
                        }
                    });
                } catch(AuditedOperationException failure) {
                    throw com.medcloud.course.paas.api.ApiFailureMapper.unwrap(failure);
                } finally {
                    if(callerTransaction && !em.getTransaction().isActive()) em.getTransaction().begin();
                }
            }
        };
    }
    @org.junit.jupiter.api.BeforeEach void unbookedSlot() {
        tx(() -> { manager.persist(new com.medcloud.course.paas.schedule.ScheduleSlot("audit-slot-a","tenant-a","department-a","doctor-a",
                java.sql.Date.valueOf("2030-05-22"),java.sql.Time.valueOf("09:00:00"),java.sql.Time.valueOf("10:00:00"),20,new java.math.BigDecimal("39.25"))); return null; });
    }
    interface Work<T> { T call(); }
    <T> T tx(Work<T> work) {
        manager.getTransaction().begin();
        try { T result=work.call(); manager.getTransaction().commit(); return result; }
        catch(RuntimeException failure) { if(manager.getTransaction().isActive()) manager.getTransaction().rollback(); manager.clear(); throw failure; }
    }
    <T> T run(AuditEventType type, CurrentActor actor, AuditOperationRunner.AuditAction<T> work) {
        AuditContext context = AuditContext.create(type,UUID.randomUUID().toString());
        context.attachActor(new CurrentActor(actor.getUserId(),actor.getRole(), actor.getRole()==Role.DOCTOR ? "tenant-a" : actor.getTenantId()));
        return AuditLocalTransactions.runner(manager).execute(context,c -> tx(() -> work.execute(c)));
    }
    List<AuditEvent> events(AuditEventType type) {
        return manager.createQuery("select e from AuditEvent e where e.eventType=:type order by e.recordedAt,e.id",AuditEvent.class).setParameter("type",type).getResultList();
    }
    String mutate(AuditEventType type, AuditContext c) {
        switch(type) {
        case APPOINTMENT_BOOK: return c==null ? new BookingService(manager).book(PATIENT,"audit-slot-a",TODAY).getId() : new BookingService(manager).book(PATIENT,"audit-slot-a",TODAY,c).getId();
        case APPOINTMENT_CANCEL: return c==null ? new BookingService(manager).cancel(PATIENT,"booked",BEFORE).getId() : new BookingService(manager).cancel(PATIENT,"booked",BEFORE,c).getId();
        case CONSULTATION_START: return c==null ? new ClinicalService(manager).start(DOCTOR,"booked",BEFORE).getId() : new ClinicalService(manager).start(DOCTOR,"booked",BEFORE,c).getId();
        case CONSULTATION_COMPLETE: return c==null ? new ClinicalService(manager).complete(DOCTOR,"progress",null,CLINICAL,"fictional advice",null,BEFORE).getId() : new ClinicalService(manager).complete(DOCTOR,"progress",null,CLINICAL,"fictional advice",null,BEFORE,c).getId();
        case PAYMENT_PAY: return c==null ? new PaymentService(manager).pay(PATIENT,"order-unpaid").getId() : new PaymentService(manager).pay(PATIENT,"order-unpaid",c).getId();
        default: throw new AssertionError(type);
        }
    }
}
