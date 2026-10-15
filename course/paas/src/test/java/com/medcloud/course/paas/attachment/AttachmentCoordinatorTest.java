package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.auth.CurrentActor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.persistence.EntityManagerFactory;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Coordinator flow tests: claim/validate/PUT/re-auth/publish, delete, download, and the
 * unknown-commit rules. Each seam call runs in its own short transaction. */
class AttachmentCoordinatorTest extends AttachmentFixture {

    // ---------- shared test doubles (package scope for the other Task 6 tests) ----------

    /** One short transaction per call, mirroring the container's REQUIRED service methods. */
    static class ServiceTransactions implements AttachmentCoordinator.AttachmentTransactions {
        private final EntityManagerFactory factory;
        ServiceTransactions(EntityManagerFactory factory) { this.factory = factory; }
        private <T> T call(Function<AttachmentDatabaseService,T> work) {
            javax.persistence.EntityManager em = factory.createEntityManager();
            try { em.getTransaction().begin(); T result = work.apply(new AttachmentDatabaseService(em)); em.getTransaction().commit(); return result; }
            finally { if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }
        }
        public UploadAttempt claim(CurrentActor actor, String uploadId, Instant now, AuditContext context) { return call(db -> db.claim(actor, uploadId, now, context)); }
        public AttachmentView publish(CurrentActor actor, String uploadId, String attemptToken, ValidatedAttachment content, Instant now, AuditContext context) { return call(db -> db.publish(actor, uploadId, attemptToken, content, now, context)); }
        public boolean abortAttempt(String uploadId, String attemptToken, Instant now) { return call(db -> db.abortAttempt(uploadId, attemptToken, now)); }
        public AttachmentSnapshot downloadCandidate(CurrentActor actor, String fileId) { return call(db -> db.downloadCandidate(actor, fileId)); }
        public void commitDownloadAuthorization(CurrentActor actor, String fileId, long expectedVersion, Instant expiresAt, Instant now, AuditContext context) { call(db -> { db.commitDownloadAuthorization(actor, fileId, expectedVersion, expiresAt, now, context); return null; }); }
        public AttachmentDeleteView logicalDelete(CurrentActor actor, String fileId, Instant now, AuditContext context) { return call(db -> db.logicalDelete(actor, fileId, now, context)); }
        public CleanupCandidate cleanupTarget(String fileId) { return call(db -> db.cleanupTarget(fileId)); }
        public void recordCleanup(String fileId, long expectedVersion, boolean confirmedAbsent, Instant now) { call(db -> { db.recordCleanup(fileId, expectedVersion, confirmedAbsent, now); return null; }); }
        public UploadOperationView operation(CurrentActor actor, String uploadId) { return call(db -> db.operation(actor, uploadId)); }
        List<CleanupCandidate> claimCleanupBatch(Instant now, int limit) { return call(db -> db.claimCleanupBatch(now, limit)); }
    }

    static class FakeObjectStore implements PrivateObjectStore {
        final Map<String,byte[]> objects = new ConcurrentHashMap<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicInteger puts = new AtomicInteger(), heads = new AtomicInteger(), deletes = new AtomicInteger(), lists = new AtomicInteger(), signs = new AtomicInteger();
        volatile RuntimeException putFailure, deleteFailure, headFailure;
        volatile List<String> enumerated = new ArrayList<>();
        volatile CountDownLatch putGate;
        public void put(String objectKey, ValidatedAttachment content) {
            events.add("put:" + objectKey); puts.incrementAndGet();
            if (putGate != null) { try { putGate.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } }
            if (putFailure != null) throw putFailure;
            objects.put(objectKey, content.getSha256() == null ? new byte[0] : content.getSha256().getBytes());
        }
        public StoreObjectInfo head(String objectKey) {
            events.add("head:" + objectKey); heads.incrementAndGet();
            if (headFailure != null) throw headFailure;
            byte[] value = objects.get(objectKey);
            return value == null ? null : new StoreObjectInfo(value.length, String.join("", Collections.nCopies(64, "a")));
        }
        public StoreDownload signGet(AttachmentSnapshot file, Instant issuedAt, int ttlSeconds) {
            events.add("sign:" + file.getObjectKey()); signs.incrementAndGet();
            return new StoreDownload("http://127.0.0.1/signed/" + file.getFileId(), issuedAt.plusSeconds(ttlSeconds));
        }
        public void delete(String objectKey) {
            events.add("delete:" + objectKey); deletes.incrementAndGet();
            if (deleteFailure != null) throw deleteFailure;
            objects.remove(objectKey);
        }
        public List<String> listKeys(String prefix, String afterKey, int limit) {
            events.add("list:" + afterKey); lists.incrementAndGet();
            List<String> keys = new ArrayList<>();
            for (String key : enumerated) if (afterKey == null || key.compareTo(afterKey) > 0) keys.add(key);
            return keys.size() > limit ? new ArrayList<>(keys.subList(0, limit)) : keys;
        }
    }

    static class FakeAuth implements AttachmentCoordinator.TokenAuthenticator {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile CurrentActor actor = PATIENT;
        volatile int failOnCall = -1;
        volatile RuntimeException failure = new SecurityException("invalid token");
        public CurrentActor authenticate(String bearerToken) {
            calls.add(String.valueOf(bearerToken));
            if (calls.size() == failOnCall) throw failure;
            return actor;
        }
    }

    static class FakeFailures implements AttachmentCoordinator.FailureSink {
        final List<String> records = new CopyOnWriteArrayList<>();
        public void record(AuditContext context, CurrentActor actor, AuditReasonCode reason) { records.add(reason.name()); }
    }

    static byte[] png() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException(ex); }
    }
    static String digest(byte[] bytes) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) s.append(String.format("%02x", b & 255));
            return s.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    static PrepareUploadRequest descriptor(byte[] bytes, String key) {
        return new PrepareUploadRequest(key, "demo.png", "image/png", bytes.length, digest(bytes));
    }

    static final class Rig {
        final ServiceTransactions db;
        final FakeObjectStore store;
        final FakeAuth auth;
        final FakeFailures failures;
        final AttachmentCoordinator coordinator;
        final AttachmentLimits limits = new AttachmentLimits();
        Rig(EntityManagerFactory factory, Clock clock, Path temp) {
            this(new ServiceTransactions(factory), new FakeObjectStore(), new FakeAuth(), new FakeFailures(), clock, temp);
        }
        Rig(ServiceTransactions db, FakeObjectStore store, FakeAuth auth, FakeFailures failures, Clock clock, Path temp) {
            this.db = db; this.store = store; this.auth = auth; this.failures = failures;
            this.coordinator = new AttachmentCoordinator(db, auth, store, new AttachmentValidator(), limits, temp, failures, clock);
        }
    }

    Rig rig() throws Exception {
        return new Rig(factory, Clock.fixed(NOW, ZoneOffset.UTC), Files.createTempDirectory("task6-coordinator-"));
    }

    String prepare(byte[] bytes, String appointment) throws Exception {
        return tx(em -> new AttachmentDatabaseService(em).preparePatient(PATIENT, appointment, descriptor(bytes, UUID.randomUUID().toString()), NOW).getUploadId());
    }

    // ---------- upload flow ----------

    @Test void claimedUploadIsExclusive() throws Exception {
        byte[] bytes = png();
        String id = prepare(bytes, "appointment1");
        Rig rig = rig();
        rig.store.putGate = new CountDownLatch(1);
        AtomicReference<UploadOperationView> first = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread firstWorker = new Thread(() -> {
            try { first.set(rig.coordinator.upload("t1", id, new ByteArrayInputStream(bytes), (long) bytes.length, context(AuditEventType.FILE_UPLOAD))); }
            catch (Throwable ex) { firstFailure.set(ex); }
        });
        firstWorker.start();
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (rig.store.puts.get() == 0 && System.nanoTime() < waitUntil) Thread.sleep(5);
        assertEquals(1, rig.store.puts.get(), "the first upload must hold the claim and reach the storage write");
        AttachmentException clash = assertThrows(AttachmentException.class,
            () -> rig.coordinator.upload("t2", id, new ByteArrayInputStream(bytes), (long) bytes.length, context(AuditEventType.FILE_UPLOAD)));
        assertEquals("UPLOAD_IN_PROGRESS", clash.getCode());
        rig.store.putGate.countDown();
        firstWorker.join(10000);
        assertNull(firstFailure.get());
        assertNotNull(first.get());
        assertEquals(AttachmentState.AVAILABLE, first.get().getState());
    }

    @Test void committedAvailableResponseLossPreservesObject() throws Exception {
        byte[] bytes = png();
        String id = prepare(bytes, "appointment1");
        Rig rig = rig();
        ServiceTransactions responseLoss = new ServiceTransactions(factory) {
            @Override public AttachmentView publish(CurrentActor actor, String uploadId, String attemptToken, ValidatedAttachment content, Instant now, AuditContext context) {
                super.publish(actor, uploadId, attemptToken, content, now, context); // commit first
                throw new IllegalStateException("response lost");
            }
        };
        AttachmentCoordinator coordinator = new AttachmentCoordinator(responseLoss, rig.auth, rig.store, new AttachmentValidator(), rig.limits, Files.createTempDirectory("task6-loss-"), rig.failures, Clock.fixed(NOW, ZoneOffset.UTC));
        UploadOperationView view = coordinator.upload("t1", id, new ByteArrayInputStream(bytes), (long) bytes.length, context(AuditEventType.FILE_UPLOAD));
        assertEquals(AttachmentState.AVAILABLE, view.getState(), "a committed upload is reported as the recovered operation");
        assertEquals(0, rig.store.deletes.get(), "a committed AVAILABLE object must never be compensated away");
        assertEquals(AttachmentState.AVAILABLE, tx(em -> em.find(FileMetadata.class, id).state));
    }

    @Test void databaseUnavailableAfterPutDoesNotGuessCommitOutcome() throws Exception {
        byte[] bytes = png();
        String id = prepare(bytes, "appointment1");
        Rig rig = rig();
        ServiceTransactions broken = new ServiceTransactions(factory) {
            @Override public AttachmentView publish(CurrentActor actor, String uploadId, String attemptToken, ValidatedAttachment content, Instant now, AuditContext context) { throw new IllegalStateException("database unavailable"); }
            @Override public boolean abortAttempt(String uploadId, String attemptToken, Instant now) { throw new IllegalStateException("database unavailable"); }
        };
        AttachmentCoordinator coordinator = new AttachmentCoordinator(broken, rig.auth, rig.store, new AttachmentValidator(), rig.limits, Files.createTempDirectory("task6-unknown-"), rig.failures, Clock.fixed(NOW, ZoneOffset.UTC));
        AttachmentException pending = assertThrows(AttachmentException.class,
            () -> coordinator.upload("t1", id, new ByteArrayInputStream(bytes), (long) bytes.length, context(AuditEventType.FILE_UPLOAD)));
        assertEquals("INTERNAL_ERROR", pending.getCode());
        assertEquals(0, rig.store.deletes.get(), "an unreadable database must never trigger a compensating delete");
        assertTrue(rig.failures.records.isEmpty(), "an unknown outcome must not be recorded as a known failure");
    }

    @Test void publicationRollbackAbortsThenDeletes() throws Exception {
        byte[] bytes = png();
        String id = prepare(bytes, "appointment1");
        Rig rig = rig();
        List<String> order = new CopyOnWriteArrayList<>();
        ServiceTransactions observing = new ServiceTransactions(factory) {
            @Override public AttachmentView publish(CurrentActor actor, String uploadId, String attemptToken, ValidatedAttachment content, Instant now, AuditContext context) { throw new AttachmentException(409, "FILE_STATE_CONFLICT"); }
            @Override public boolean abortAttempt(String uploadId, String attemptToken, Instant now) { order.add("abort"); return super.abortAttempt(uploadId, attemptToken, now); }
        };
        FakeObjectStore store = new FakeObjectStore() {
            @Override public void delete(String objectKey) { order.add("delete"); super.delete(objectKey); }
        };
        AttachmentCoordinator coordinator = new AttachmentCoordinator(observing, rig.auth, store, new AttachmentValidator(), rig.limits, Files.createTempDirectory("task6-rollback-"), rig.failures, Clock.fixed(NOW, ZoneOffset.UTC));
        AttachmentException rejected = assertThrows(AttachmentException.class,
            () -> coordinator.upload("t1", id, new ByteArrayInputStream(bytes), (long) bytes.length, context(AuditEventType.FILE_UPLOAD)));
        assertEquals("FILE_STATE_CONFLICT", rejected.getCode());
        assertEquals(Arrays.asList("abort", "delete"), order, "the attempt must be aborted in a new transaction before any compensating delete");
        assertEquals(Collections.singletonList("CONFLICT"), rig.failures.records);
    }

    @Test void expiredBearerAfterStoreWaitCannotPublishOrIssueNewUrl() throws Exception {
        byte[] bytes = png();
        String id = prepare(bytes, "appointment1");
        Rig rig = rig();
        rig.auth.failOnCall = 2; // authenticate ok, re-authenticate after the store wait fails
        AttachmentException expired = assertThrows(AttachmentException.class,
            () -> rig.coordinator.upload("t1", id, new ByteArrayInputStream(bytes), (long) bytes.length, context(AuditEventType.FILE_UPLOAD)));
        assertEquals("UNAUTHORIZED", expired.getCode());
        assertEquals(AttachmentState.ABORTED, tx(em -> em.find(FileMetadata.class, id).state));
        assertEquals(1, rig.store.deletes.get(), "the compensating delete runs after the fenced abort");
        // the same rule guards downloads: a stale bearer must not produce a URL
        String fileId = prepare(bytes, "appointment2");
        UploadAttempt attempt = tx(em -> new AttachmentDatabaseService(em).claim(PATIENT, fileId, NOW, context(AuditEventType.FILE_UPLOAD)));
        tx(em -> new AttachmentDatabaseService(em).publish(PATIENT, fileId, attempt.getAttemptToken(), new ValidatedAttachment(null, "demo.png", "image/png", bytes.length, digest(bytes)), NOW, context(AuditEventType.FILE_UPLOAD)));
        rig.store.objects.put(attempt.getObjectKey(), new byte[0]);
        rig.auth.failOnCall = rig.auth.calls.size() + 2;
        AttachmentException noUrl = assertThrows(AttachmentException.class,
            () -> rig.coordinator.authorizeDownload("t3", fileId, context(AuditEventType.FILE_DOWNLOAD_AUTHORIZE)));
        assertEquals("UNAUTHORIZED", noUrl.getCode());
    }

    @Test void uploadRejectsBeforeContentWhenClaimFails() throws Exception {
        byte[] bytes = png();
        Rig rig = rig();
        AtomicInteger reads = new AtomicInteger();
        InputStream counting = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read() { reads.incrementAndGet(); return super.read(); }
        };
        assertThrows(AttachmentException.class,
            () -> rig.coordinator.upload("t1", "missing-upload", counting, (long) bytes.length, context(AuditEventType.FILE_UPLOAD)));
        assertEquals(0, reads.get(), "authentication and claim must run before any content is consumed");
    }
}
