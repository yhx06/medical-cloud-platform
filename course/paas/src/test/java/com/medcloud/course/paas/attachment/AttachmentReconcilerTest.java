package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.auth.CurrentActor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static com.medcloud.course.paas.attachment.AttachmentCoordinatorTest.*;

/** Reconciler tests: persistent pending work, tombstone rechecks, late PUTs, backoff
 * schedule, bounded batches, ownership-verified enumeration and attempt fencing. */
class AttachmentReconcilerTest extends AttachmentFixture {

    static final class CleanupTransactions implements AttachmentReconciler.CleanupTransactions {
        private final ServiceTransactions db;
        CleanupTransactions(ServiceTransactions db) { this.db = db; }
        public List<CleanupCandidate> claimCleanupBatch(Instant now, int limit) { return db.claimCleanupBatch(now, limit); }
        public void recordCleanup(String fileId, long expectedVersion, boolean confirmedAbsent, Instant now) { db.recordCleanup(fileId, expectedVersion, confirmedAbsent, now); }
        public CleanupCandidate cleanupTarget(String fileId) { return db.cleanupTarget(fileId); }
    }

    AttachmentReconciler reconciler(FakeObjectStore store) {
        return new AttachmentReconciler(new CleanupTransactions(new ServiceTransactions(factory)), store, "course-attachments/");
    }

    String prepareClaimed(byte[] bytes) throws Exception {
        String id = tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT, "appointment1", descriptor(bytes, UUID.randomUUID().toString()), NOW).getUploadId());
        UploadAttempt attempt = tx(em -> new AttachmentDatabaseService(em).claim(PATIENT, id, NOW, context(AuditEventType.FILE_UPLOAD)));
        return attempt.getObjectKey();
    }

    String pendingDelete(byte[] bytes) throws Exception {
        String id = tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT, "appointment1", descriptor(bytes, UUID.randomUUID().toString()), NOW).getUploadId());
        UploadAttempt attempt = tx(em -> new AttachmentDatabaseService(em).claim(PATIENT, id, NOW, context(AuditEventType.FILE_UPLOAD)));
        tx(em -> new AttachmentDatabaseService(em).publish(PATIENT, id, attempt.getAttemptToken(),
            new ValidatedAttachment(null, "demo.png", "image/png", bytes.length, digest(bytes)), NOW, context(AuditEventType.FILE_UPLOAD)));
        tx(em -> new AttachmentDatabaseService(em).logicalDelete(PATIENT, id, NOW, context(AuditEventType.FILE_DELETE)));
        return attempt.getObjectKey();
    }

    @Test void restartedReconcilerFindsPersistentPendingWork() throws Exception {
        byte[] bytes = png();
        String objectKey = prepareClaimed(bytes); // UPLOADING with a lease ending NOW+600
        FakeObjectStore store = new FakeObjectStore();
        store.objects.put(objectKey, new byte[0]);
        AttachmentReconciler restarted = reconciler(store); // fresh instance: no in-memory queue
        int processed = restarted.runBatch(NOW.plusSeconds(601));
        assertEquals(1, processed, "work claimed before a restart must be found by a new reconciler");
        assertEquals(1, store.deletes.get());
        assertFalse(store.objects.containsKey(objectKey));
    }

    @Test void latePutAfterAbsentHeadIsEventuallyRemoved() throws Exception {
        byte[] bytes = png();
        String objectKey = pendingDelete(bytes); // DELETE_PENDING, object already gone
        FakeObjectStore store = new FakeObjectStore();
        AttachmentReconciler reconciler = reconciler(store);
        assertEquals(1, reconciler.runBatch(NOW));
        assertEquals(0, store.deletes.get(), "an absent object needs no delete");
        // a late PUT lands after the absence was confirmed
        store.objects.put(objectKey, new byte[0]);
        assertEquals(0, reconciler.runBatch(NOW.plusSeconds(1799)), "the tombstone waits for its recheck window");
        assertEquals(1, reconciler.runBatch(NOW.plusSeconds(1801)), "the tombstone recheck finds the late PUT");
        assertEquals(1, store.deletes.get());
        assertFalse(store.objects.containsKey(objectKey));
    }

    @Test void unknownEnumeratedKeyIsNotDeleted() throws Exception {
        byte[] bytes = png();
        pendingDelete(bytes);
        FakeObjectStore store = new FakeObjectStore();
        store.enumerated = new ArrayList<>(Collections.singletonList("course-attachments/foreign-object"));
        store.objects.put("course-attachments/foreign-object", new byte[0]);
        AttachmentReconciler reconciler = reconciler(store);
        reconciler.runBatch(NOW.plusSeconds(3600));
        assertEquals(0, store.deletes.get(), "an unregistered key must never be deleted");
        assertTrue(store.objects.containsKey("course-attachments/foreign-object"));
    }

    @Test void backoffIsOneTwoFourEightSixteenThirtyMinutes() throws Exception {
        byte[] bytes = png();
        String objectKey = pendingDelete(bytes);
        FakeObjectStore store = new FakeObjectStore();
        store.objects.put(objectKey, new byte[0]);
        store.deleteFailure = new IllegalStateException("storage offline");
        AttachmentReconciler reconciler = reconciler(store);
        long[] schedule = {1, 2, 4, 8, 16, 30, 30};
        List<Long> intervals = new ArrayList<>();
        Instant at = NOW.plusSeconds(1);
        Instant last = null;
        for (int attempt = 0; attempt < 7; attempt++) {
            if (last != null) assertEquals(0, reconciler.runBatch(last.plusSeconds(30)), "no retry inside the backoff window");
            assertEquals(1, reconciler.runBatch(at), "the scheduled retry claims the row");
            if (last != null) intervals.add(java.time.Duration.between(last, at).toMinutes());
            last = at;
            at = at.plusSeconds(schedule[attempt] * 60).plusSeconds(1);
        }
        assertEquals(Arrays.asList(1L, 2L, 4L, 8L, 16L, 30L), intervals, "failures back off 1/2/4/8/16/30 minutes");
        assertEquals(7, store.deletes.get(), "each scheduled retry attempts the delete again");
    }

    @Test void cleanupBatchAtMost100AndTombstonesRecheckedAfterThirtyMinutes() throws Exception {
        byte[] bytes = png();
        FakeObjectStore store = new FakeObjectStore();
        for (int i = 0; i < 150; i++) store.objects.put(pendingDelete(bytes), new byte[0]);
        AttachmentReconciler reconciler = reconciler(store);
        assertEquals(100, reconciler.runBatch(NOW), "one batch never exceeds 100 rows");
        assertEquals(100, store.deletes.get());
        assertEquals(50, reconciler.runBatch(NOW.plusSeconds(2)), "the rest of the backlog waits for the next batch");
        assertEquals(0, reconciler.runBatch(NOW.plusSeconds(1799)), "cleaned tombstones wait for the 30 minute recheck");
        assertEquals(100, reconciler.runBatch(NOW.plusSeconds(1803)), "cleaned tombstones are rechecked after 30 minutes, never forgotten");
    }

    @Test void attemptFenceRejectsLatePublication() throws Exception {
        byte[] bytes = png();
        String id = tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT, "appointment1", descriptor(bytes, UUID.randomUUID().toString()), NOW).getUploadId());
        UploadAttempt attempt = tx(em -> new AttachmentDatabaseService(em).claim(PATIENT, id, NOW, context(AuditEventType.FILE_UPLOAD)));
        FakeObjectStore store = new FakeObjectStore();
        AttachmentReconciler reconciler = reconciler(store);
        assertEquals(1, reconciler.runBatch(NOW.plusSeconds(601)), "the expired claim is fenced to ABORTED");
        assertEquals(AttachmentState.ABORTED, tx(em -> em.find(FileMetadata.class, id).state));
        AttachmentException late = assertThrows(AttachmentException.class,
            () -> tx(em -> new AttachmentDatabaseService(em).publish(PATIENT, id, attempt.getAttemptToken(),
                new ValidatedAttachment(null, "demo.png", "image/png", bytes.length, digest(bytes)), NOW.plusSeconds(602), context(AuditEventType.FILE_UPLOAD))));
        assertEquals("FILE_STATE_CONFLICT", late.getCode(), "a late publication must not revive an aborted attempt");
        assertEquals(AttachmentState.ABORTED, tx(em -> em.find(FileMetadata.class, id).state));
    }
}
