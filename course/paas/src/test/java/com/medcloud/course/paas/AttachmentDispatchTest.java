package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.*;
import com.medcloud.course.paas.attachment.*;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.auth.CurrentActor;
import org.jboss.resteasy.core.Dispatcher;
import org.jboss.resteasy.mock.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP dispatch behaviour of the eight attachment routes. */
class AttachmentDispatchTest extends AuditFixture {
    Dispatcher dispatcher;
    com.medcloud.course.paas.auth.AuthService auth;

    /** The route layer is under test; coordinator outcomes are scripted per case. */
    static final class StubCoordinator extends AttachmentCoordinator {
        volatile UploadOperationView uploadView;
        volatile AttachmentDeleteView deleteView;
        volatile DownloadAuthorizationView downloadView;
        volatile RuntimeException uploadFailure, deleteFailure, downloadFailure;
        @Override public UploadOperationView upload(String bearerToken, String uploadId, java.io.InputStream content, Long contentLength, AuditContext context) {
            if (uploadFailure != null) throw uploadFailure;
            return uploadView;
        }
        @Override public AttachmentDeleteView deletePatient(String bearerToken, String fileId, AuditContext context) {
            if (deleteFailure != null) throw deleteFailure;
            return deleteView;
        }
        @Override public DownloadAuthorizationView authorizeDownload(String bearerToken, String fileId, AuditContext context) {
            if (downloadFailure != null) throw downloadFailure;
            return downloadView;
        }
    }

    static final class PerCallDatabaseService extends AttachmentDatabaseService {
        private final EntityManagerFactory factory;
        PerCallDatabaseService(EntityManagerFactory factory) { this.factory = factory; }
        private <T> T call(java.util.function.Function<AttachmentDatabaseService,T> work) {
            javax.persistence.EntityManager em = factory.createEntityManager();
            try { em.getTransaction().begin(); T result = work.apply(new AttachmentDatabaseService(em)); em.getTransaction().commit(); return result; }
            finally { if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }
        }
        @Override public AttachmentPage listForPatient(CurrentActor actor, String appointmentId, String page, String size) { return call(db -> db.listForPatient(actor, appointmentId, page, size)); }
        @Override public AttachmentPage listForDoctor(CurrentActor actor, String appointmentId, String page, String size) { return call(db -> db.listForDoctor(actor, appointmentId, page, size)); }
        @Override public UploadOperationView preparePatient(CurrentActor actor, String appointmentId, PrepareUploadRequest request, Instant now) { return call(db -> db.preparePatient(actor, appointmentId, request, now)); }
        @Override public UploadOperationView prepareDoctor(CurrentActor actor, String recordId, PrepareUploadRequest request, Instant now) { return call(db -> db.prepareDoctor(actor, recordId, request, now)); }
        @Override public UploadOperationView operation(CurrentActor actor, String uploadId) { return call(db -> db.operation(actor, uploadId)); }
    }

    static final String PATIENT2 = "patient2", APPOINTMENT = "appointment1", APPOINTMENT2 = "appointment2";
    StubCoordinator coordinator = new StubCoordinator();

    @BeforeEach void dispatchFixture() {
        auth = new com.medcloud.course.paas.auth.AuthService(manager, SECRET);
        String hash = org.mindrot.jbcrypt.BCrypt.hashpw(PASSWORD, org.mindrot.jbcrypt.BCrypt.gensalt(4));
        manager.getTransaction().begin();
        manager.persist(new com.medcloud.course.paas.auth.Account(PATIENT2, PATIENT2, hash, Role.PATIENT, null));
        manager.persist(new com.medcloud.course.paas.tenant.Department("department", A, "Demo"));
        manager.persist(new com.medcloud.course.paas.tenant.DoctorProfile("doctor", A, "department", "Demo doctor"));
        manager.persist(new com.medcloud.course.paas.schedule.ScheduleSlot("slot1", A, "department", "doctor",
            Date.valueOf("2030-01-02"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 10, BigDecimal.TEN));
        manager.persist(new com.medcloud.course.paas.schedule.ScheduleSlot("slot2", A, "department", "doctor",
            Date.valueOf("2030-01-03"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 10, BigDecimal.TEN));
        manager.persist(new com.medcloud.course.paas.appointment.Appointment(APPOINTMENT, "patient", "slot1", A, "doctor",
            BigDecimal.TEN, Date.valueOf("2030-01-01"), Instant.parse("2030-01-01T00:00:00Z")));
        manager.persist(new com.medcloud.course.paas.appointment.Appointment(APPOINTMENT2, PATIENT2, "slot2", A, "doctor",
            BigDecimal.TEN, Date.valueOf("2030-01-01"), Instant.parse("2030-01-01T00:00:00Z")));
        manager.getTransaction().commit();
        manager.clear();
        dispatcher = MockDispatcherFactory.createDispatcher();
        dispatcher.getProviderFactory().registerProvider(ApiFailureMapper.class);
        dispatcher.getProviderFactory().registerProvider(AuditRequestFilter.class);
        dispatcher.getProviderFactory().registerProvider(AuditResponseFilter.class);
        dispatcher.getProviderFactory().registerProvider(PlatformTenantDispatchTest.JsonWriter.class);
        dispatcher.getRegistry().addSingletonResource(new AttachmentResource(auth, new PerCallDatabaseService(factory), coordinator, AuditLocalTransactions.runner(manager)));
    }

    MockHttpResponse invoke(String method, String path, String authorization, String contentType, byte[] body) throws Exception {
        MockHttpRequest request = method.equals("GET") ? MockHttpRequest.get(path) : method.equals("PUT") ? MockHttpRequest.put(path) : MockHttpRequest.post(path);
        request.header("X-Request-ID", "00000000-0000-0000-0000-000000000099");
        if (authorization != null) request.header("Authorization", authorization);
        if (contentType != null) request.header("Content-Type", contentType);
        if (body != null) request.content(body);
        MockHttpResponse response = new MockHttpResponse();
        dispatcher.invoke(request, response);
        return response;
    }

    String bearer(String user) {
        return "Bearer " + AuditLocalTransactions.login(manager, auth, new com.medcloud.course.contract.LoginRequest(user, PASSWORD)).getAccessToken();
    }

    void assertErrorId(MockHttpResponse response, String code) throws Exception {
        JsonNode error = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals(code, error.path("code").asText());
        String id = error.path("requestId").asText();
        UUID.fromString(id);
        Object header = response.getOutputHeaders().getFirst("X-Request-ID");
        if (header != null) assertEquals(id, header.toString());
        assertFalse(error.has("data"));
    }

    @Test void anonymousInvalidPaginationReturns401() throws Exception {
        MockHttpResponse response = invoke("GET", "/patient/appointments/" + APPOINTMENT + "/files?page=abc&pageSize=2147483648", null, null, null);
        assertEquals(401, response.getStatus());
        assertErrorId(response, "UNAUTHORIZED");
    }

    @Test void wrongOwnerInvalidPaginationReturns403() throws Exception {
        MockHttpResponse response = invoke("GET", "/patient/appointments/" + APPOINTMENT + "/files?page=abc&pageSize=bad", bearer("patient2"), null, null);
        assertEquals(403, response.getStatus());
        assertErrorId(response, "FORBIDDEN");
    }

    @Test void rawBinaryRouteAcceptsOctetStream() throws Exception {
        coordinator.uploadView = new UploadOperationView("upload-1", AttachmentState.AVAILABLE, null);
        MockHttpResponse response = invoke("PUT", "/files/uploads/upload-1/content", bearer("patient"), "application/octet-stream", new byte[]{1, 2, 3});
        assertEquals(200, response.getStatus());
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals("upload", body.path("message").asText());
        assertEquals("AVAILABLE", body.path("data").path("state").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/patient/appointments/" + APPOINTMENT + "/files/uploads", "/files/uploads/upload-1/content"})
    void jsonOnly415MessageStaysOffBinaryRoute(String path) throws Exception {
        MockHttpResponse response = invoke(path.endsWith("/content") ? "PUT" : "POST", path, bearer("patient"), "text/plain", "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(415, response.getStatus());
        JsonNode error = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.path("code").asText());
        if (path.endsWith("/content")) {
            assertEquals("Unsupported media type", error.path("message").asText(), "the JSON-only wording must not reach the binary route");
        } else {
            assertEquals("JSON content type required", error.path("message").asText());
        }
    }

    @Test void deleteAndDownloadCarryStatusAndNoStore() throws Exception {
        coordinator.deleteView = new AttachmentDeleteView("file-1", AttachmentState.DELETE_PENDING);
        MockHttpResponse pending = invoke("POST", "/patient/files/file-1/delete", bearer("patient"), null, null);
        assertEquals(202, pending.getStatus());
        coordinator.deleteView = new AttachmentDeleteView("file-1", AttachmentState.DELETED);
        MockHttpResponse deleted = invoke("POST", "/patient/files/file-1/delete", bearer("patient"), null, null);
        assertEquals(200, deleted.getStatus());
        coordinator.downloadView = new DownloadAuthorizationView("file-1", "http://127.0.0.1/signed/file-1", "2030-01-01T00:05:00Z");
        MockHttpResponse download = invoke("POST", "/files/file-1/download-url", bearer("patient"), null, null);
        assertEquals(200, download.getStatus());
        assertEquals("no-store", download.getOutputHeaders().getFirst("Cache-Control"));
    }

    @Test void attachmentErrorsKeepStableCodesOverHttp() throws Exception {
        coordinator.uploadFailure = new AttachmentException(413, "FILE_TOO_LARGE");
        MockHttpResponse tooLarge = invoke("PUT", "/files/uploads/upload-1/content", bearer("patient"), "application/octet-stream", new byte[]{1});
        assertEquals(413, tooLarge.getStatus());
        assertErrorId(tooLarge, "FILE_TOO_LARGE");
        coordinator.downloadFailure = new AttachmentException(401, "UNAUTHORIZED");
        MockHttpResponse expired = invoke("POST", "/files/file-1/download-url", bearer("patient"), null, null);
        assertEquals(401, expired.getStatus());
        assertErrorId(expired, "UNAUTHORIZED");
        String payload = expired.getContentAsString();
        assertFalse(payload.contains("http://127.0.0.1"), "error bodies never carry storage locations");
    }
}
