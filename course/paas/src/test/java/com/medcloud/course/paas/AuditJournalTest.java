package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.CurrentActor;
import org.junit.jupiter.api.*;
import javax.persistence.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditJournalTest {
    private static final String REQUEST = "12345678-1234-4234-8234-123456789abc";
    private static final Instant NOW = Instant.parse("2030-05-20T16:01:02.123456Z");
    private EntityManagerFactory factory;
    private EntityManager em;

    @BeforeEach void open() {
        factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        em = factory.createEntityManager();
    }
    @AfterEach void close() {
        if (em.getTransaction().isActive()) em.getTransaction().rollback();
        em.close(); factory.close();
    }
    private AuditContext doctor() {
        AuditContext c = AuditContext.create(AuditEventType.CONSULTATION_COMPLETE, REQUEST);
        c.attachActor(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"));
        return c;
    }
    private AuditEvent onlyRow() {
        em.clear();
        List<AuditEvent> rows = em.createQuery("select a from AuditEvent a", AuditEvent.class).getResultList();
        assertEquals(1, rows.size()); return rows.get(0);
    }
    @Test void successfulAppendRequiresTransactionAndUsesScalarWhitelist() {
        AuditJournal journal = new AuditJournal(em);
        assertThrows(AuditInfrastructureException.class, () -> journal.append(doctor(), "record-a", AuditResult.SUCCESS, NOW));
        em.getTransaction().begin();
        journal.append(doctor(), "record-a", AuditResult.SUCCESS, NOW);
        em.getTransaction().commit();
        AuditEvent row = onlyRow();
        assertEquals(UUID.fromString(row.getId()).toString(), row.getId());
        assertEquals(REQUEST, row.getRequestId());
        assertEquals("doctor-a", row.getActorId()); assertEquals(Role.DOCTOR, row.getActorRole());
        assertEquals("tenant-a", row.getTenantId()); assertEquals("record-a", row.getObjectId());
        assertEquals(AuditEventType.CONSULTATION_COMPLETE, row.getEventType());
        assertEquals(AuditObjectType.CLINICAL_RECORD, row.getObjectType());
        assertEquals(AuditResult.SUCCESS, row.getResult()); assertEquals(AuditReasonCode.OK, row.getReasonCode());
        assertEquals(NOW, row.getRecordedAt().toInstant());
        Set<String> columns = new HashSet<>();
        for (Object name : em.createNativeQuery("select column_name from information_schema.columns where table_name='AUDIT_EVENT'").getResultList()) columns.add(name.toString());
        assertEquals(new HashSet<>(Arrays.asList("ID", "REQUEST_ID", "ACTOR_ID", "ACTOR_ROLE", "TENANT_ID", "EVENT_TYPE", "OBJECT_TYPE", "OBJECT_ID", "RESULT", "REASON_CODE", "RECORDED_AT")), columns);
    }
    @Test void rollbackRemovesSuccessRow() {
        em.getTransaction().begin(); new AuditJournal(em).append(doctor(), "record-a", AuditResult.SUCCESS, NOW);
        em.getTransaction().rollback(); em.clear();
        assertEquals(0L, em.createQuery("select count(a) from AuditEvent a", Long.class).getSingleResult());
    }
    @Test void duplicateRequestEventIsRejected() {
        em.getTransaction().begin(); new AuditJournal(em).append(doctor(), "record-a", AuditResult.SUCCESS, NOW); em.getTransaction().commit();
        em.getTransaction().begin();
        assertThrows(AuditInfrastructureException.class, () -> new AuditJournal(em).append(doctor(), "record-b", AuditResult.SUCCESS, NOW));
        em.getTransaction().rollback(); assertEquals("record-a", onlyRow().getObjectId());
    }
    @Test void noChangeUsesUnchangedReasonAndSuccessWriterRejectsFailed() {
        em.getTransaction().begin();
        assertThrows(IllegalArgumentException.class, () -> new AuditJournal(em).append(doctor(), "record-a", AuditResult.FAILED, NOW));
        new AuditJournal(em).append(doctor(), "record-a", AuditResult.NO_CHANGE, NOW); em.getTransaction().commit();
        assertEquals(AuditResult.NO_CHANGE, onlyRow().getResult()); assertEquals(AuditReasonCode.UNCHANGED, onlyRow().getReasonCode());
    }
    @Test void allThirteenEventsUseServerDefinedObjectTypes() {
        String[] events = {"LOGIN", "TENANT_STATUS_CHANGE", "DEPARTMENT_CREATE", "DEPARTMENT_UPDATE", "DOCTOR_CREATE", "DOCTOR_UPDATE", "SLOT_CREATE", "SLOT_UPDATE", "APPOINTMENT_BOOK", "APPOINTMENT_CANCEL", "CONSULTATION_START", "CONSULTATION_COMPLETE", "PAYMENT_PAY"};
        String[] objects = {"ACCOUNT", "TENANT", "DEPARTMENT", "DEPARTMENT", "DOCTOR_ACCOUNT", "DOCTOR_ACCOUNT", "SLOT", "SLOT", "APPOINTMENT", "APPOINTMENT", "APPOINTMENT", "CLINICAL_RECORD", "PAYMENT_ORDER"};
        em.getTransaction().begin();
        for (int i=0; i<events.length; i++) {
            AuditContext c = AuditContext.create(AuditEventType.valueOf(events[i]), UUID.randomUUID().toString());
            c.attachActor(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"));
            new AuditJournal(em).append(c, "object-a", AuditResult.SUCCESS, NOW);
        }
        em.getTransaction().commit(); em.clear();
        for (int i=0; i<events.length; i++) {
            AuditEvent row = em.createQuery("select a from AuditEvent a where a.eventType=:event", AuditEvent.class)
                    .setParameter("event", AuditEventType.valueOf(events[i])).getSingleResult();
            assertEquals(AuditObjectType.valueOf(objects[i]), row.getObjectType());
        }
    }
    @Test void physicalIndexesHaveIndependentExpectedColumnOrder() throws Exception {
        org.hibernate.Session session = em.unwrap(org.hibernate.Session.class);
        Map<String, SortedMap<Integer,String>> indexes = session.doReturningWork(connection -> {
            Map<String, SortedMap<Integer,String>> result = new HashMap<>();
            try (java.sql.ResultSet rows = connection.getMetaData().getIndexInfo(null, null, "AUDIT_EVENT", false, false)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    if (name != null) result.computeIfAbsent(name, key -> new TreeMap<>()).put(rows.getInt("ORDINAL_POSITION"), rows.getString("COLUMN_NAME"));
                }
            }
            return result;
        });
        assertEquals(Arrays.asList("RECORDED_AT", "ID"), new ArrayList<>(indexes.get("IDX_AUDIT_RECORDED").values()));
        assertEquals(Arrays.asList("TENANT_ID", "RECORDED_AT", "ID"), new ArrayList<>(indexes.get("IDX_AUDIT_TENANT").values()));
        assertEquals(Arrays.asList("EVENT_TYPE", "RESULT", "RECORDED_AT", "ID"), new ArrayList<>(indexes.get("IDX_AUDIT_EVENT_RESULT").values()));
    }
    @Test void failureAlwaysHasNullObjectId() {
        em.getTransaction().begin(); new AuditFailureJournal(em).append(doctor(), AuditReasonCode.CONFLICT, NOW); em.getTransaction().commit();
        AuditEvent row = onlyRow(); assertNull(row.getObjectId()); assertEquals(AuditResult.FAILED, row.getResult());
        assertEquals(AuditReasonCode.CONFLICT, row.getReasonCode()); assertEquals("tenant-a", row.getTenantId());
    }
    @Test void loginFailureIsAnonymousEvenAfterVerifiedActorAndTenant() {
        AuditContext c = AuditContext.create(AuditEventType.LOGIN, REQUEST);
        c.attachActor(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a")); c.attachVerifiedTenant("tenant-a");
        em.getTransaction().begin(); new AuditFailureJournal(em).append(c, AuditReasonCode.INTERNAL_ERROR, NOW); em.getTransaction().commit();
        AuditEvent row = onlyRow(); assertNull(row.getActorId()); assertNull(row.getActorRole()); assertNull(row.getTenantId()); assertNull(row.getObjectId());
    }
    @Test void platformFailureIsGlobalEvenAfterTargetHospitalAttached() {
        AuditContext c = AuditContext.create(AuditEventType.TENANT_STATUS_CHANGE, REQUEST);
        c.attachActor(new CurrentActor("platform-a", Role.PLATFORM_ADMIN, null)); c.attachVerifiedTenant("tenant-target");
        em.getTransaction().begin(); new AuditFailureJournal(em).append(c, AuditReasonCode.CONFLICT, NOW); em.getTransaction().commit();
        AuditEvent row = onlyRow(); assertNull(row.getTenantId()); assertEquals("platform-a", row.getActorId()); assertNull(row.getObjectId());
    }
    @Test void patientFailureUsesOnlyPreviouslyVerifiedOwnedHospital() {
        AuditContext c = AuditContext.create(AuditEventType.APPOINTMENT_CANCEL, REQUEST);
        c.attachActor(new CurrentActor("patient-a", Role.PATIENT, null));
        em.getTransaction().begin(); new AuditFailureJournal(em).append(c, AuditReasonCode.FORBIDDEN, NOW); em.getTransaction().commit();
        assertNull(onlyRow().getTenantId());
        AuditContext verified = AuditContext.create(AuditEventType.APPOINTMENT_CANCEL, UUID.randomUUID().toString());
        verified.attachActor(new CurrentActor("patient-a", Role.PATIENT, null)); verified.attachVerifiedTenant("owned-tenant");
        em.getTransaction().begin(); new AuditFailureJournal(em).append(verified, AuditReasonCode.CONFLICT, NOW); em.getTransaction().commit(); em.clear();
        AuditEvent row = em.createQuery("select a from AuditEvent a where a.requestId=:id", AuditEvent.class).setParameter("id", verified.getRequestId()).getSingleResult();
        assertEquals("owned-tenant", row.getTenantId()); assertNull(row.getObjectId());
    }
    @Test void malformedContextsAndOversizedScalarsAreRejectedBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> AuditContext.create(AuditEventType.LOGIN, "sensitive-sentinel"));
        assertThrows(IllegalArgumentException.class, () -> AuditContext.create(AuditEventType.LOGIN, "1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class, () -> doctor().attachVerifiedTenant(String.join("", Collections.nCopies(37, "x"))));
        assertThrows(IllegalArgumentException.class, () -> doctor().attachActor(new CurrentActor("sensitive-sentinel-too-long-to-be-an-id", Role.DOCTOR, "tenant")));
        em.getTransaction().begin();
        assertThrows(IllegalArgumentException.class, () -> new AuditJournal(em).append(doctor(), String.join("", Collections.nCopies(37, "x")), AuditResult.SUCCESS, NOW));
        assertThrows(IllegalArgumentException.class, () -> new AuditFailureJournal(em).append(doctor(), AuditReasonCode.OK, NOW));
        assertThrows(IllegalArgumentException.class, () -> new AuditJournal(em).append(doctor(), "object", AuditResult.SUCCESS, null));
        assertEquals(0L, em.createQuery("select count(a) from AuditEvent a", Long.class).getSingleResult());
    }
}
