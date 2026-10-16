package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.api.*;
import com.medcloud.course.paas.appointment.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.consultation.*;
import com.medcloud.course.paas.order.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditFailureScopeTest extends AuditClinicalFixture {
    // A non-owned/nonexistent path must never lend its hospital or object to a FAILED event.
    @ParameterizedTest @CsvSource({"APPOINTMENT_CANCEL,booked", "APPOINTMENT_CANCEL,missing", "PAYMENT_PAY,order-unpaid", "PAYMENT_PAY,missing"})
    void patientDeniedTargetDoesNotSupplyFailureTenant(AuditEventType type,String target) {
        List<String> before=snapshot();
        RuntimeException failure=assertThrows(RuntimeException.class, () -> run(type,OTHER,c -> type==AuditEventType.APPOINTMENT_CANCEL ?
                new BookingService(manager).cancel(OTHER,target,BEFORE,c) : new PaymentService(manager).pay(OTHER,target,c)));
        assertEquals(403,new ApiFailureMapper().toResponse(failure).getStatus());
        AuditEvent e=only(type); assertEquals("patient-b",e.getActorId()); assertNull(e.getTenantId()); assertNull(e.getObjectId());
        assertEquals(AuditResult.FAILED,e.getResult()); assertEquals(AuditReasonCode.FORBIDDEN,e.getReasonCode());
        manager.clear(); assertEquals(before,snapshot());
    }

    @ParameterizedTest @EnumSource(value=AuditEventType.class,names={"APPOINTMENT_CANCEL","PAYMENT_PAY"})
    void patientVerifiedOwnedTargetFailureUsesTrustedTenantButNullObjectId(AuditEventType type) {
        setTenantStatus("DISABLED"); List<String> before=snapshot();
        RuntimeException failure=assertThrows(RuntimeException.class,() -> run(type,PATIENT,c -> mutate(type,c)));
        assertEquals(409,new ApiFailureMapper().toResponse(failure).getStatus());
        AuditEvent e=only(type); assertEquals("patient-a",e.getActorId()); assertEquals("tenant-a",e.getTenantId()); assertNull(e.getObjectId());
        assertEquals(AuditReasonCode.CONFLICT,e.getReasonCode()); manager.clear(); assertEquals(before,snapshot());
    }

    @ParameterizedTest @EnumSource(value=AuditEventType.class,names={"CONSULTATION_START","CONSULTATION_COMPLETE"})
    void doctorCrossTenantFailureBelongsOnlyToAuthenticatedHospital(AuditEventType type) {
        CurrentActor doctorB=new CurrentActor("doctor-b",Role.DOCTOR,"tenant-b"); List<String> before=snapshot();
        AuditContext c=AuditContext.create(type,UUID.randomUUID().toString()); c.attachActor(doctorB);
        RuntimeException failure=assertThrows(RuntimeException.class,() -> AuditLocalTransactions.runner(manager).execute(c,scope -> tx(() ->
                type==AuditEventType.CONSULTATION_START ? new ClinicalService(manager).start(doctorB,"booked",BEFORE,scope) :
                new ClinicalService(manager).complete(doctorB,"progress",null,CLINICAL,"fictional advice",null,BEFORE,scope))));
        assertEquals(403,new ApiFailureMapper().toResponse(failure).getStatus());
        AuditEvent e=only(type); assertEquals("doctor-b",e.getActorId()); assertEquals("tenant-b",e.getTenantId()); assertNull(e.getObjectId());
        manager.clear(); assertEquals(before,snapshot());
    }

    // Real unique request constraint forces the actual journal insert/flush to fail after mutation.
    @ParameterizedTest @EnumSource(value=AuditEventType.class,names={"APPOINTMENT_BOOK","APPOINTMENT_CANCEL","CONSULTATION_START","CONSULTATION_COMPLETE","PAYMENT_PAY"})
    void auditInsertFailureRollsBackBookingCancelStartCompleteAndPay(AuditEventType type) {
        AuditContext c=AuditContext.create(type,UUID.randomUUID().toString());
        c.attachActor(new CurrentActor(type.name().startsWith("CONSULTATION") ? "doctor-a" : "patient-a", type.name().startsWith("CONSULTATION") ? Role.DOCTOR : Role.PATIENT,type.name().startsWith("CONSULTATION") ? "tenant-a" : null));
        c.attachVerifiedTenant("tenant-a");
        tx(() -> { new AuditJournal(manager).append(c,"existing",AuditResult.SUCCESS,BEFORE); return null; });
        List<String> before=snapshot();
        RuntimeException failure=assertThrows(AuditInfrastructureException.class,() -> tx(() -> mutate(type,c)));
        assertEquals(500,new ApiFailureMapper().toResponse(failure).getStatus());
        manager.clear(); assertEquals(before,snapshot()); assertEquals(1,events(type).size());
    }

    @Test void repeatPaymentStillChecksFrozenHospitalBeforeNoChange() {
        setTenantStatus("DISABLED"); java.sql.Timestamp before=manager.find(PaymentOrder.class,"order-paid").getPaidAt();
        assertThrows(RuntimeException.class,() -> run(AuditEventType.PAYMENT_PAY,PATIENT,c -> new PaymentService(manager).pay(PATIENT,"order-paid",c)));
        assertEquals(AuditResult.FAILED,only(AuditEventType.PAYMENT_PAY).getResult());
        assertEquals(before,manager.find(PaymentOrder.class,"order-paid").getPaidAt());
    }

    // If presentation moves inside execute, this successful commit produces an erroneous FAILED event.
    @ParameterizedTest @ValueSource(strings={"clinicalView","paymentWritable","paymentView"})
    void postCommitViewFailureKeepsSuccessOnly(String stage) throws Exception {
        try(AuditLogCapture logs=new AuditLogCapture()) {
            AuthService auth=new AuthService(manager,SECRET);
            String bearer="Bearer " + AuditLocalTransactions.login(manager,auth,new LoginRequest(stage.equals("clinicalView") ? "doctor_a" : "patient_a",PASSWORD)).getAccessToken();
            if(stage.equals("clinicalView")) {
                ClinicalService clinical=new ClinicalService(manager) {
                    @Override public ClinicalRecord complete(CurrentActor a,String id,String complaint,String diagnosis,String advice,String notes,java.time.Instant at,AuditContext c,Long expectedDraftRevision) {
                        ClinicalRecord committed=tx(() -> super.complete(a,id,complaint,diagnosis,advice,notes,at,c,expectedDraftRevision));
                        return new ClinicalRecord(committed.getId(),id,"tenant-a","doctor-a","patient-a",null,CLINICAL,"fictional advice",null,at) {
                            @Override public String getDiagnosis() { throw new IllegalStateException(CLINICAL); }
                        };
                    }
                };
                RuntimeException failure=assertThrows(RuntimeException.class,() -> new ClinicalResource(auth,clinical,AuditLocalTransactions.runner(manager)).complete(bearer,"progress",new CompleteConsultationRequest(CLINICAL,"fictional advice")));
                new ApiFailureMapper().toResponse(failure);
                java.util.List<ClinicalRecord> records=manager.createQuery("select r from ClinicalRecord r where r.appointmentId='progress'",ClinicalRecord.class).getResultList();
                assertEquals(1,records.size(),"business must commit before presentation fails");
                assertEquals(CLINICAL,records.get(0).getDiagnosis());
                assertEquals(AuditResult.SUCCESS,only(AuditEventType.CONSULTATION_COMPLETE).getResult());
            } else {
                PaymentService payments=new PaymentService(manager) {
                    @Override public PaymentOrder pay(CurrentActor a,String id,AuditContext c) {
                        PaymentOrder committed=tx(() -> super.pay(a,id,c));
                        if(stage.equals("paymentWritable")) return committed;
                        return new PaymentOrder(committed.getId(),committed.getAppointmentId(),"patient-a","tenant-a",committed.getAmount()) {
                            @Override public java.math.BigDecimal getAmount() { throw new IllegalStateException(CLINICAL); }
                        };
                    }
                    @Override public boolean businessWritable(CurrentActor a,String id) {
                        if(stage.equals("paymentWritable")) throw new IllegalStateException(CLINICAL);
                        return super.businessWritable(a,id);
                    }
                };
                RuntimeException failure=assertThrows(RuntimeException.class,() -> new PaymentResource(auth,payments,AuditLocalTransactions.runner(manager)).pay(bearer,"order-unpaid"));
                new ApiFailureMapper().toResponse(failure);
                assertEquals("PAID",manager.find(PaymentOrder.class,"order-unpaid").getStatus());
                assertEquals(AuditResult.SUCCESS,only(AuditEventType.PAYMENT_PAY).getResult());
            }
            assertFalse(new ObjectMapper().writeValueAsString(manager.createQuery("select e from AuditEvent e",AuditEvent.class).getResultList()).contains(CLINICAL));
            logs.assertAbsent(CLINICAL);
        }
    }

    @ParameterizedTest @CsvSource({"/patient/appointments,APPOINTMENT_BOOK", "/patient/appointments/attack-id/cancel,APPOINTMENT_CANCEL", "/doctor/appointments/attack-id/start,CONSULTATION_START", "/doctor/appointments/attack-id/complete,CONSULTATION_COMPLETE", "/patient/orders/attack-id/pay,PAYMENT_PAY"})
    void clinicalHttpFailuresUseServerRequestIdAndAnonymousScope(String path,AuditEventType type) throws Exception {
        org.jboss.resteasy.core.Dispatcher d=org.jboss.resteasy.mock.MockDispatcherFactory.createDispatcher();
        d.getProviderFactory().registerProvider(ApiFailureMapper.class); d.getProviderFactory().registerProvider(AuditRequestFilter.class);
        d.getProviderFactory().registerProvider(AuditResponseFilter.class); d.getProviderFactory().registerProvider(PlatformTenantDispatchTest.JsonWriter.class);
        d.getProviderFactory().registerProvider(AuditManagementTest.JsonReader.class);
        AuthService auth=new AuthService(manager,SECRET); AuditOperationRunner runner=AuditLocalTransactions.runner(manager);
        d.getRegistry().addSingletonResource(new AppointmentResource(auth,new BookingService(manager),runner));
        d.getRegistry().addSingletonResource(new ClinicalResource(auth,new ClinicalService(manager),runner));
        d.getRegistry().addSingletonResource(new PaymentResource(auth,new PaymentService(manager),runner));
        org.jboss.resteasy.mock.MockHttpRequest request=org.jboss.resteasy.mock.MockHttpRequest.post(path);
        request.header("Content-Type","application/json"); request.content("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.header("Authorization","Bearer invalid_clinical_token"); request.header("X-Request-ID","00000000-0000-0000-0000-000000000099");
        org.jboss.resteasy.mock.MockHttpResponse response=new org.jboss.resteasy.mock.MockHttpResponse(); d.invoke(request,response);
        assertEquals(401,response.getStatus());
        String id=new ObjectMapper().readTree(response.getContentAsString()).path("requestId").asText(); UUID.fromString(id);
        assertNotEquals("00000000-0000-0000-0000-000000000099",id); assertEquals(id,response.getOutputHeaders().getFirst("X-Request-ID"));
        AuditEvent e=only(type); assertEquals(id,e.getRequestId()); assertNull(e.getActorId()); assertNull(e.getActorRole()); assertNull(e.getTenantId()); assertNull(e.getObjectId());
        assertEquals(AuditResult.FAILED,e.getResult()); assertEquals(AuditReasonCode.UNAUTHORIZED,e.getReasonCode());
    }

    AuditEvent only(AuditEventType type) { List<AuditEvent> rows=events(type); assertEquals(1,rows.size()); return rows.get(0); }
}
