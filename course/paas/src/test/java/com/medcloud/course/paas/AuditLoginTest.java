package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class AuditLoginTest extends TenantFixture {
    private static final String USERNAME = "login_username_sentinel";
    private static final String PASSWORD_SOURCE = "login_password_sentinel";
    private final AuditLogCapture logs = new AuditLogCapture();
    @AfterEach void safeLogs() { try { logs.assertAbsent(USERNAME, PASSWORD_SOURCE, PASSWORD); } finally { logs.close(); } }

    @Test void successfulLegacyLoginCannotSilentlySkipItsRequiredAuditTransaction() {
        AuthService auth = new AuthService(manager, SECRET);
        RuntimeException failure = assertThrows(AuditInfrastructureException.class, () -> auth.login(new LoginRequest("patient_a", PASSWORD)));
        assertEquals(500, new ApiFailureMapper().toResponse(failure).getStatus());
        assertEquals(0L, manager.createQuery("select count(e) from AuditEvent e",Long.class).getSingleResult());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"login_username_sentinel", "unknown_username_sentinel"})
    void badLoginIsAnonymousRegardlessOfUsernameExistence(String username) throws Exception {
        manager.getTransaction().begin();
        manager.persist(new Account("sentinel-account", USERNAME, org.mindrot.jbcrypt.BCrypt.hashpw(PASSWORD_SOURCE, org.mindrot.jbcrypt.BCrypt.gensalt(4)), Role.HOSPITAL_ADMIN, "tenant-a"));
        manager.getTransaction().commit();
        AuthResource resource = new AuthResource(new AuthService(manager, SECRET), AuditLocalTransactions.runner(manager));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> resource.login(new LoginRequest(username, "wrong_password_sentinel")));
        assertEquals(401, new ApiFailureMapper().toResponse(failure).getStatus());
        java.util.List<AuditEvent> rows = manager.createQuery("select e from AuditEvent e", AuditEvent.class).getResultList();
        assertEquals(1, rows.size()); AuditEvent e = rows.get(0);
        assertNull(e.getActorId()); assertNull(e.getActorRole()); assertNull(e.getTenantId()); assertNull(e.getObjectId());
        assertEquals(AuditEventType.LOGIN, e.getEventType()); assertEquals(AuditResult.FAILED, e.getResult()); assertEquals(AuditReasonCode.UNAUTHORIZED, e.getReasonCode());
        String serialized = new ObjectMapper().writeValueAsString(rows);
        assertFalse(serialized.contains(username)); assertFalse(serialized.contains(PASSWORD_SOURCE)); assertFalse(serialized.contains("wrong_password_sentinel"));
        logs.assertAbsent(username, "wrong_password_sentinel");
    }

    // Missing success writes or a hospital inferred for patient/platform fails independently per role.
    @ParameterizedTest
    @CsvSource({"patient_a,patient-a,PATIENT", "platform,platform,PLATFORM_ADMIN", "doctor_a,doctor-a,DOCTOR", "admin_a,admin-a,HOSPITAL_ADMIN"})
    void loginRecordsVerifiedAccountAndHospitalOnly(String username, String id, Role role) throws Exception {
        manager.getTransaction().begin();
        Account source = new Account("sentinel-account", USERNAME, org.mindrot.jbcrypt.BCrypt.hashpw(PASSWORD_SOURCE, org.mindrot.jbcrypt.BCrypt.gensalt(4)), Role.PATIENT, null);
        manager.persist(source); manager.getTransaction().commit();
        AuthService auth = new AuthService(manager, SECRET);
        manager.getTransaction().begin();
        LoginResponse response = auth.login(new LoginRequest(username, PASSWORD));
        manager.getTransaction().commit();
        java.util.List<AuditEvent> rows = manager.createQuery("select e from AuditEvent e", AuditEvent.class).getResultList();
        assertEquals(1L, rows.size());
        AuditEvent e = rows.get(0);
        assertEquals(AuditEventType.LOGIN, e.getEventType()); assertEquals(AuditObjectType.ACCOUNT, e.getObjectType());
        assertEquals(id, e.getActorId()); assertEquals(id, e.getObjectId()); assertEquals(role, e.getActorRole());
        if (role == Role.PATIENT || role == Role.PLATFORM_ADMIN) assertNull(e.getTenantId()); else assertEquals("tenant-a", e.getTenantId());
        assertEquals(AuditResult.SUCCESS, e.getResult()); assertEquals(AuditReasonCode.OK, e.getReasonCode());
        assertNotNull(e.getRecordedAt()); assertNotNull(response.getAccessToken());
        auth.authenticate(response.getAccessToken()); auth.authenticateAuditReader(response.getAccessToken());
        assertEquals(1L, manager.createQuery("select count(e) from AuditEvent e", Long.class).getSingleResult());
        manager.getTransaction().begin(); auth.login(new LoginRequest(USERNAME, PASSWORD_SOURCE)); manager.getTransaction().commit();
        String audit = new ObjectMapper().writeValueAsString(manager.createQuery("select e from AuditEvent e", AuditEvent.class).getResultList());
        assertFalse(audit.contains(USERNAME)); assertFalse(audit.contains(PASSWORD_SOURCE)); assertFalse(audit.contains(response.getAccessToken()));
        logs.assertAbsent(response.getAccessToken());
    }
}

// RESOURCE_LOCAL simulation only: it does not establish container REQUIRES_NEW/JTA behavior.
final class AuditLocalTransactions {
    static LoginResponse login(javax.persistence.EntityManager manager, AuthService auth, LoginRequest request) {
        boolean owns = !manager.getTransaction().isActive();
        if (owns) manager.getTransaction().begin();
        try { LoginResponse response = auth.login(request); if (owns) manager.getTransaction().commit(); return response; }
        catch (RuntimeException failure) { if (owns && manager.getTransaction().isActive()) manager.getTransaction().rollback(); throw failure; }
    }
    static AuditOperationRunner runner(javax.persistence.EntityManager manager) {
        return new AuditOperationRunner(new AuditFailureJournal(manager) {
            @Override public void append(AuditContext c, AuditReasonCode reason, java.time.Instant at) {
                assertFalse(manager.getTransaction().isActive(), "business transaction must end before failure writer starts");
                manager.getTransaction().begin();
                try { super.append(c, reason, at); manager.getTransaction().commit(); }
                catch (RuntimeException failure) { if (manager.getTransaction().isActive()) manager.getTransaction().rollback(); throw failure; }
            }
        });
    }
}

final class AuditLogCapture implements AutoCloseable {
    private final java.util.List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
    private final java.util.logging.Logger[] loggers = {java.util.logging.Logger.getLogger(AuditOperationRunner.class.getName()), java.util.logging.Logger.getLogger(ApiFailureMapper.class.getName())};
    private final java.util.logging.Handler capture = new java.util.logging.Handler() {
        public void publish(java.util.logging.LogRecord record) { records.add(record); }
        public void flush() {} public void close() {}
    };
    AuditLogCapture() { for (java.util.logging.Logger logger : loggers) logger.addHandler(capture); }
    void assertAbsent(String... sources) { for (java.util.logging.LogRecord record : records) { assertNull(record.getThrown()); for (String source : sources) if (source != null) assertFalse(record.getMessage().contains(source)); } }
    public void close() { for (java.util.logging.Logger logger : loggers) logger.removeHandler(capture); }
}
