package com.medcloud.course.paas.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.api.AttachmentResource;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.audit.AuditFailureJournal;
import com.medcloud.course.paas.audit.AuditOperationRunner;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static com.medcloud.course.paas.attachment.AttachmentCoordinatorTest.*;

/** Route-level tests against the real database service and coordinator. */
class AttachmentResourceTest extends AttachmentFixture {

    static final class TokenAuth extends AuthService {
        final Map<String,CurrentActor> tokens = new LinkedHashMap<>();
        TokenAuth(EntityManager manager) { super(manager, "attachment-resource-test-secret"); }
        @Override public CurrentActor authenticate(String token) {
            CurrentActor actor = tokens.get(token);
            if (actor == null) throw new SecurityException("invalid token");
            return actor;
        }
    }

    /** One short transaction per call, mirroring the container's REQUIRED service. */
    static final class PerCallDatabaseService extends AttachmentDatabaseService {
        private final EntityManagerFactory factory;
        PerCallDatabaseService(EntityManagerFactory factory) { this.factory = factory; }
        private <T> T call(Function<AttachmentDatabaseService,T> work) {
            EntityManager em = factory.createEntityManager();
            try { em.getTransaction().begin(); T result = work.apply(new AttachmentDatabaseService(em)); em.getTransaction().commit(); return result; }
            finally { if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }
        }
        @Override public AttachmentPage listForPatient(CurrentActor actor, String appointmentId, String page, String size) { return call(db -> db.listForPatient(actor, appointmentId, page, size)); }
        @Override public AttachmentPage listForDoctor(CurrentActor actor, String appointmentId, String page, String size) { return call(db -> db.listForDoctor(actor, appointmentId, page, size)); }
        @Override public UploadOperationView preparePatient(CurrentActor actor, String appointmentId, PrepareUploadRequest request, Instant now) { return call(db -> db.preparePatient(actor, appointmentId, request, NOW)); }
        @Override public UploadOperationView prepareDoctor(CurrentActor actor, String recordId, PrepareUploadRequest request, Instant now) { return call(db -> db.prepareDoctor(actor, recordId, request, NOW)); }
        @Override public UploadOperationView operation(CurrentActor actor, String uploadId) { return call(db -> db.operation(actor, uploadId)); }
    }

    static final class LocalFailures implements AttachmentCoordinator.FailureSink {
        final List<String> records = new ArrayList<>();
        public void record(AuditContext context, CurrentActor actor, AuditReasonCode reason) { records.add(reason.name()); }
    }

    TokenAuth auth;
    FakeObjectStore store = new FakeObjectStore();
    LocalFailures failures = new LocalFailures();
    List<String> runnerFailures = new ArrayList<>();
    AttachmentCoordinator coordinator;
    AttachmentResource resource;

    @Override protected void beforeSeed() {
        auth = new TokenAuth(factory.createEntityManager());
        auth.tokens.put("patient", PATIENT);
        auth.tokens.put("doctor", DOCTOR);
        auth.tokens.put("other", new CurrentActor("other", Role.PATIENT, null));
        auth.tokens.put("foreign-doctor", new CurrentActor("other", Role.DOCTOR, "tenant"));
    }

    void openResource() throws Exception {
        coordinator = new AttachmentCoordinator(new ServiceTransactions(factory), auth::authenticate, store, new AttachmentValidator(),
            new AttachmentLimits(), Files.createTempDirectory("task6-route-"), failures, Clock.fixed(NOW, ZoneOffset.UTC));
        AuditFailureJournal journal = new AuditFailureJournal() {
            @Override public void append(AuditContext context, AuditReasonCode reason, Instant at) { runnerFailures.add(reason.name()); }
        };
        resource = new AttachmentResource(auth, new PerCallDatabaseService(factory), coordinator, new AuditOperationRunner(journal));
    }

    String prepareId(byte[] bytes) {
        return tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT, "appointment1", descriptor(bytes, UUID.randomUUID().toString()), NOW).getUploadId());
    }

    static Stream<Arguments> routeMatrix() {
        return Stream.of(
            Arguments.of("anonymous-list", null, 401, "UNAUTHORIZED"),
            Arguments.of("foreign-patient-list", "other", 403, "FORBIDDEN"),
            Arguments.of("same-hospital-unassigned-doctor-list", "foreign-doctor", 403, "FORBIDDEN")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routeMatrix")
    void listAuthorizationMatrix(String scenario, String token, int status, String code) throws Exception {
        openResource();
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> resource.listPatientFiles(token == null ? null : "Bearer " + token, "appointment1", "bad-page", "bad-size"));
        Response mapped = new ApiFailureMapper().toResponse(failure);
        assertEquals(status, mapped.getStatus(), scenario);
        assertEquals(code, ((ApiError) mapped.getEntity()).getCode(), scenario);
    }

    @Test void anonymousInvalidPaginationReturns401() throws Exception {
        openResource();
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> resource.listPatientFiles(null, "appointment1", "abc", "2147483648"));
        Response mapped = new ApiFailureMapper().toResponse(failure);
        assertEquals(401, mapped.getStatus());
        assertEquals("UNAUTHORIZED", ((ApiError) mapped.getEntity()).getCode());
    }

    @Test void wrongOwnerInvalidPaginationReturns403() throws Exception {
        openResource();
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> resource.listPatientFiles("Bearer other", "appointment1", "abc", "2147483648"));
        Response mapped = new ApiFailureMapper().toResponse(failure);
        assertEquals(403, mapped.getStatus());
        assertEquals("FORBIDDEN", ((ApiError) mapped.getEntity()).getCode());
    }

    @Test void operationRecoveryDoesNotDependOnListPage() throws Exception {
        openResource();
        byte[] bytes = png();
        String id = prepareId(bytes);
        tx(em -> new AttachmentDatabaseService(em).claim(PATIENT, id, NOW, context(AuditEventType.FILE_UPLOAD)));
        Response before = resource.uploadOperation("Bearer patient", id);
        assertEquals(200, before.getStatus());
        assertEquals(AttachmentState.UPLOADING, ((UploadOperationView) ((ApiResponse<?>) before.getEntity()).getData()).getState());
        Response emptyList = resource.listPatientFiles("Bearer patient", "appointment1", "5", "20");
        assertEquals(0, ((AttachmentPage) ((ApiResponse<?>) emptyList.getEntity()).getData()).getItems().size());
        Response recovered = resource.uploadOperation("Bearer patient", id);
        assertEquals(id, ((UploadOperationView) ((ApiResponse<?>) recovered.getEntity()).getData()).getUploadId());
    }

    @Test void downloadResponseIsNoStoreAndNotInList() throws Exception {
        openResource();
        byte[] bytes = png();
        String id = prepareId(bytes);
        UploadAttempt attempt = tx(em -> new AttachmentDatabaseService(em).claim(PATIENT, id, NOW, context(AuditEventType.FILE_UPLOAD)));
        tx(em -> new AttachmentDatabaseService(em).publish(PATIENT, id, attempt.getAttemptToken(),
            new ValidatedAttachment(null, "demo.png", "image/png", bytes.length, digest(bytes)), NOW, context(AuditEventType.FILE_UPLOAD)));
        store.objects.put(attempt.getObjectKey(), new byte[0]);
        Response download = resource.downloadUrl("Bearer patient", id);
        assertEquals(200, download.getStatus());
        assertEquals("no-store", download.getMetadata().getFirst("Cache-Control"));
        DownloadAuthorizationView signed = (DownloadAuthorizationView) ((ApiResponse<?>) download.getEntity()).getData();
        assertNotNull(signed.getUrl());
        Response list = resource.listPatientFiles("Bearer patient", "appointment1", null, null);
        String payload = new ObjectMapper().writeValueAsString(list.getEntity());
        assertTrue(payload.contains("demo.png"), "the published file appears in the list");
        assertFalse(payload.contains(signed.getUrl()), "the signed URL must never appear in list metadata");
        assertFalse(payload.contains(attempt.getObjectKey()), "object keys must never appear in list metadata");
    }

    @Test void attachmentErrorsKeepStableCodesReasonsAndRequestId() throws Exception {
        openResource();
        byte[] bytes = png();
        String id = prepareId(bytes);
        store.putFailure = new IllegalStateException("storage offline");
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> resource.uploadContent("Bearer patient", id, new ByteArrayInputStream(bytes), (long) bytes.length));
        Response mapped = new ApiFailureMapper().toResponse(failure);
        assertEquals(503, mapped.getStatus());
        ApiError error = (ApiError) mapped.getEntity();
        assertEquals("OBJECT_STORE_UNAVAILABLE", error.getCode());
        assertNotNull(error.getRequestId());
        assertEquals(Collections.singletonList("INTERNAL_ERROR"), failures.records);
        // descriptor errors stay 400 with their own code and a BAD_REQUEST failure reason
        failures.records.clear();
        RuntimeException bad = assertThrows(RuntimeException.class,
            () -> resource.preparePatientUpload("Bearer patient", "appointment1",
                new PrepareUploadRequest(UUID.randomUUID().toString(), "demo.txt", "text/plain", 10, String.join("", Collections.nCopies(64, "a")))));
        Response mappedBad = new ApiFailureMapper().toResponse(bad);
        assertEquals(400, mappedBad.getStatus());
        assertEquals("UPLOAD_DESCRIPTOR_INVALID", ((ApiError) mappedBad.getEntity()).getCode());
        assertEquals(Collections.singletonList("BAD_REQUEST"), runnerFailures);
    }

    @Test void deleteReportsPendingThenDeleted() throws Exception {
        openResource();
        byte[] bytes = png();
        String id = prepareId(bytes);
        UploadAttempt attempt = tx(em -> new AttachmentDatabaseService(em).claim(PATIENT, id, NOW, context(AuditEventType.FILE_UPLOAD)));
        tx(em -> new AttachmentDatabaseService(em).publish(PATIENT, id, attempt.getAttemptToken(),
            new ValidatedAttachment(null, "demo.png", "image/png", bytes.length, digest(bytes)), NOW, context(AuditEventType.FILE_UPLOAD)));
        store.objects.put(attempt.getObjectKey(), new byte[0]);
        store.deleteFailure = new IllegalStateException("storage offline");
        Response pending = resource.deletePatientFile("Bearer patient", id);
        assertEquals(202, pending.getStatus(), "a physical cleanup failure is reported as accepted-pending");
        assertEquals(AttachmentState.DELETE_PENDING, ((AttachmentDeleteView) ((ApiResponse<?>) pending.getEntity()).getData()).getState());
        assertTrue(failures.records.isEmpty(), "physical cleanup failure is not a business failure");
        store.deleteFailure = null;
        Response deleted = resource.deletePatientFile("Bearer patient", id);
        assertEquals(200, deleted.getStatus(), "a completed physical cleanup reports the deleted state");
        assertEquals(AttachmentState.DELETED, ((AttachmentDeleteView) ((ApiResponse<?>) deleted.getEntity()).getData()).getState());
    }

    @Test void foreignUploadAndFileIdsAreRejected() throws Exception {
        openResource();
        byte[] bytes = png();
        String id = prepareId(bytes);
        assertThrows(RuntimeException.class,
            () -> resource.uploadContent("Bearer other", id, new ByteArrayInputStream(bytes), (long) bytes.length));
        String foreign = UUID.randomUUID().toString();
        assertThrows(RuntimeException.class, () -> resource.uploadOperation("Bearer patient", foreign));
        assertThrows(RuntimeException.class, () -> resource.deletePatientFile("Bearer patient", foreign));
        assertThrows(RuntimeException.class, () -> resource.downloadUrl("Bearer patient", foreign));
    }
}
