package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.auth.CurrentActor;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static com.medcloud.course.paas.attachment.AttachmentCoordinatorTest.*;

/** Audit-boundary tests: known failures record exactly once, committed outcomes never
 * accumulate a business failure afterwards. */
class AttachmentAuditTest extends AttachmentFixture {

    ServiceTransactions transactions() { return new ServiceTransactions(factory); }

    Rig rig(ServiceTransactions db, FakeObjectStore store, FakeAuth auth, FakeFailures failures) throws Exception {
        return new Rig(db, store, auth, failures, Clock.fixed(NOW, ZoneOffset.UTC), Files.createTempDirectory("task6-audit-"));
    }

    static List<Object[]> auditRows(EntityManagerFactory factory, String requestId) {
        javax.persistence.EntityManager em = factory.createEntityManager();
        try {
            return em.createQuery("select e.result, e.reasonCode from AuditEvent e where e.requestId=:rid", Object[].class)
                .setParameter("rid", requestId).getResultList();
        } finally { em.close(); }
    }

    String published(String id, byte[] bytes) throws Exception {
        UploadAttempt attempt = tx(em -> new AttachmentDatabaseService(em).claim(PATIENT, id, NOW, context(AuditEventType.FILE_UPLOAD)));
        tx(em -> new AttachmentDatabaseService(em).publish(PATIENT, id, attempt.getAttemptToken(),
            new ValidatedAttachment(null, "demo.png", "image/png", bytes.length, digest(bytes)), NOW, context(AuditEventType.FILE_UPLOAD)));
        return attempt.getObjectKey();
    }

    @Test void downloadAuditFailureReturnsNoUrl() throws Exception {
        byte[] bytes = png();
        String id = tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT, "appointment1", descriptor(bytes, UUID.randomUUID().toString()), NOW).getUploadId());
        String objectKey = published(id, bytes);
        FakeObjectStore store = new FakeObjectStore();
        store.objects.put(objectKey, new byte[0]);
        ServiceTransactions auditFails = new ServiceTransactions(factory) {
            @Override public void commitDownloadAuthorization(CurrentActor actor, String fileId, long expectedVersion, Instant expiresAt, Instant now, AuditContext context) {
                throw new com.medcloud.course.paas.audit.AuditInfrastructureException(new IllegalStateException("audit write failed"));
            }
        };
        FakeAuth auth = new FakeAuth();
        FakeFailures failures = new FakeFailures();
        Rig rig = rig(auditFails, store, auth, failures);
        AuditContext context = context(AuditEventType.FILE_DOWNLOAD_AUTHORIZE);
        AttachmentException refused = assertThrows(AttachmentException.class, () -> rig.coordinator.authorizeDownload("t1", id, context));
        assertEquals("INTERNAL_ERROR", refused.getCode());
        assertEquals(1, store.signs.get(), "a URL may be prepared but never returned");
        assertEquals(Collections.singletonList("INTERNAL_ERROR"), failures.records, "the failed authorization is a known non-success");
    }

    @Test void physicalDeleteFailureDoesNotCreateBusinessFailure() throws Exception {
        byte[] bytes = png();
        String id = tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT, "appointment1", descriptor(bytes, UUID.randomUUID().toString()), NOW).getUploadId());
        String objectKey = published(id, bytes);
        FakeObjectStore store = new FakeObjectStore();
        store.objects.put(objectKey, new byte[0]);
        store.deleteFailure = new IllegalStateException("storage offline");
        ServiceTransactions db = transactions();
        FakeAuth auth = new FakeAuth();
        FakeFailures failures = new FakeFailures();
        Rig rig = rig(db, store, auth, failures);
        AuditContext context = context(AuditEventType.FILE_DELETE);
        AttachmentDeleteView view = rig.coordinator.deletePatient("t1", id, context);
        assertEquals(AttachmentState.DELETE_PENDING, view.getState(), "physical cleanup failure keeps the logical delete pending (202 semantics)");
        assertTrue(failures.records.isEmpty(), "a cleanup failure is not a business failure");
        List<Object[]> rows = auditRows(factory, context.getRequestId());
        assertEquals(1, rows.size(), "exactly one audit row for the request");
        assertEquals(AuditResult.SUCCESS, rows.get(0)[0], "only the SUCCESS of the accepted logical delete is recorded");
        assertEquals(AttachmentState.DELETE_PENDING, tx(em -> em.find(FileMetadata.class, id).state));
        store.deleteFailure = null;
        long version = tx(em -> em.find(FileMetadata.class, id).version);
        tx(em -> { new AttachmentDatabaseService(em).recordCleanup(id, version, true, NOW); return null; });
        assertEquals(AttachmentState.DELETED, tx(em -> em.find(FileMetadata.class, id).state));
    }

    @Test void presentationFailureAfterAvailableDoesNotAppendFailure() throws Exception {
        byte[] bytes = png();
        String id = tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT, "appointment1", descriptor(bytes, UUID.randomUUID().toString()), NOW).getUploadId());
        ServiceTransactions presentationFails = new ServiceTransactions(factory) {
            @Override public UploadOperationView operation(CurrentActor actor, String uploadId) { throw new IllegalStateException("presentation failed"); }
        };
        FakeObjectStore store = new FakeObjectStore();
        FakeAuth auth = new FakeAuth();
        FakeFailures failures = new FakeFailures();
        Rig rig = rig(presentationFails, store, auth, failures);
        IllegalStateException failed = assertThrows(IllegalStateException.class,
            () -> rig.coordinator.upload("t1", id, new ByteArrayInputStream(bytes), (long) bytes.length, context(AuditEventType.FILE_UPLOAD)));
        assertEquals("presentation failed", failed.getMessage());
        assertTrue(failures.records.isEmpty(), "a post-commit presentation failure must not append a business failure");
        assertEquals(0, store.deletes.get(), "the committed AVAILABLE object stays");
        assertEquals(AttachmentState.AVAILABLE, tx(em -> em.find(FileMetadata.class, id).state));
    }
}
