package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.auth.AccessDeniedException;
import org.junit.jupiter.api.*;
import javax.ejb.EJBException;
import javax.persistence.*;
import javax.ws.rs.core.Response;
import java.time.Instant;
import java.util.*;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditOperationRunnerTest {
    private static final String ID = "12345678-1234-4234-8234-123456789abc";
    private EntityManagerFactory factory; private EntityManager em;
    private AuditOperationRunner runner;
    @BeforeEach void open() {
        factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest"); em = factory.createEntityManager();
        // Explicit local transaction adapter: production uses the injected REQUIRES_NEW EJB proxy.
        runner = new AuditOperationRunner(new AuditFailureJournal(em) {
            @Override public void append(AuditContext c, AuditReasonCode reason, Instant at) {
                em.getTransaction().begin();
                try { super.append(c, reason, at); em.getTransaction().commit(); }
                catch (RuntimeException failure) { if (em.getTransaction().isActive()) em.getTransaction().rollback(); throw failure; }
            }
        });
    }
    @AfterEach void close() { em.close(); factory.close(); }
    @Test void wrappedFailuresKeepStatusReasonAndRequestId() {
        RuntimeException[] failures = {new SecurityException("sensitive-sentinel"), new AccessDeniedException(), new IllegalArgumentException(), new IllegalStateException(), new AuditInfrastructureException(new IllegalStateException("sensitive-sentinel"))};
        int[] statuses = {401,403,400,409,500};
        AuditReasonCode[] reasons = {AuditReasonCode.UNAUTHORIZED, AuditReasonCode.FORBIDDEN, AuditReasonCode.BAD_REQUEST, AuditReasonCode.CONFLICT, AuditReasonCode.INTERNAL_ERROR};
        for (int i=0; i<failures.length; i++) {
            String id = UUID.randomUUID().toString(); RuntimeException original = failures[i];
            AuditedOperationException audited = assertThrows(AuditedOperationException.class,
                    () -> runner.execute(AuditContext.create(AuditEventType.LOGIN, id), c -> { throw new EJBException(original); }));
            Response response = new ApiFailureMapper().toResponse(new EJBException(audited));
            assertEquals(statuses[i], response.getStatus()); assertEquals(id, ((ApiError) response.getEntity()).getRequestId()); assertEquals(id, response.getHeaderString("X-Request-ID"));
            AuditEvent row = em.createQuery("select a from AuditEvent a where a.requestId=:id", AuditEvent.class).setParameter("id",id).getSingleResult();
            assertEquals(reasons[i], row.getReasonCode()); assertNull(row.getObjectId());
        }
    }
    @Test void failureWriterThrowsWithoutChangingOriginalFailure() {
        List<LogRecord> logs = new ArrayList<>();
        Handler capture = new Handler() { public void publish(LogRecord r) { logs.add(r); } public void flush() {} public void close() {} };
        Logger logger = Logger.getLogger(AuditOperationRunner.class.getName()); logger.addHandler(capture);
        try {
            AuditOperationRunner broken = new AuditOperationRunner(new AuditFailureJournal(em) {
                @Override public void append(AuditContext c, AuditReasonCode reason, Instant at) { throw new EJBException(new IllegalStateException("sensitive-sentinel")); }
            });
            SecurityException original = new SecurityException("sensitive-sentinel");
            AuditedOperationException failure = assertThrows(AuditedOperationException.class, () -> broken.execute(AuditContext.create(AuditEventType.LOGIN, ID), c -> { throw original; }));
            assertSame(original, failure.getCause()); assertEquals(ID, failure.getRequestId());
            assertEquals(401, new ApiFailureMapper().toResponse(failure).getStatus());
            assertEquals(1, logs.size()); assertNull(logs.get(0).getThrown());
            assertTrue(logs.get(0).getMessage().contains(ID)); assertTrue(logs.get(0).getMessage().contains("AUDIT_WRITE_FAILED"));
            assertFalse(logs.get(0).getMessage().contains("sensitive-sentinel"));
        } finally { logger.removeHandler(capture); }
    }
    @Test void postActionPresentationFailureIsNotBusinessFailure() {
        String value = runner.execute(AuditContext.create(AuditEventType.LOGIN, ID), c -> {
            em.getTransaction().begin(); new AuditJournal(em).append(c, "account-a", AuditResult.SUCCESS, Instant.now()); em.getTransaction().commit(); return "committed";
        });
        assertEquals("committed", value);
        assertThrows(IllegalStateException.class, () -> { throw new IllegalStateException("presentation unavailable"); });
        List<AuditEvent> rows = em.createQuery("select a from AuditEvent a",AuditEvent.class).getResultList();
        assertEquals(1, rows.size()); assertEquals(AuditResult.SUCCESS, rows.get(0).getResult());
    }
    @Test void internalFailureLogsOnlySafeRequestIdAndWhitelistType() {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(ApiFailureMapper.class.getName());
        Handler capture = new Handler() { public void publish(LogRecord r) { records.add(r); } public void flush() {} public void close() {} };
        logger.addHandler(capture);
        try {
            Response response = new ApiFailureMapper().toResponse(new AuditedOperationException(new RuntimeException("sensitive-sentinel"), ID));
            assertEquals(500, response.getStatus()); assertEquals(1, records.size()); assertNull(records.get(0).getThrown());
            assertTrue(records.get(0).getMessage().contains(ID)); assertFalse(records.get(0).getMessage().contains("sensitive-sentinel"));
        } finally { logger.removeHandler(capture); }
    }
    @Test void realFailureJournalCannotOverwriteOriginalStatusWhenDuplicateInsertFails() {
        AuditContext context = AuditContext.create(AuditEventType.LOGIN, ID);
        runner.execute(context, c -> {
            em.getTransaction().begin(); new AuditJournal(em).append(c, "account-a", AuditResult.SUCCESS, Instant.now()); em.getTransaction().commit(); return "committed";
        });
        AuditedOperationException original = assertThrows(AuditedOperationException.class,
                () -> runner.execute(context, c -> { throw new SecurityException("sensitive-sentinel"); }));
        assertEquals(401, new ApiFailureMapper().toResponse(original).getStatus());
        em.clear();
        List<AuditEvent> rows = em.createQuery("select a from AuditEvent a", AuditEvent.class).getResultList();
        assertEquals(1, rows.size()); assertEquals(AuditResult.SUCCESS, rows.get(0).getResult());
    }
}
