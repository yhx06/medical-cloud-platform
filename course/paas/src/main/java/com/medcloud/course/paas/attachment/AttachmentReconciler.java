package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.AttachmentState;

import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.ejb.Timer;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Recovery and cleanup for attachment intents. Work is driven by the persistent row list
 * (never by memory): fenced aborts, compensating deletes, tombstone rechecks and a bounded
 * ownership-verified sweep of the controlled object prefix. Unregistered keys are counted
 * for review and never deleted.
 */
@Singleton
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class AttachmentReconciler {
    private static final Logger LOG = Logger.getLogger(AttachmentReconciler.class.getName());
    static final String OBJECT_KEY_ROOT = "course-attachments/";
    private static final int BATCH_LIMIT = 100;
    private static final int SWEEP_PAGES = 10;

    /** Short database calls only; each runs in its own transaction. */
    interface CleanupTransactions {
        List<CleanupCandidate> claimCleanupBatch(Instant now, int limit);
        void recordCleanup(String fileId, long expectedVersion, boolean confirmedAbsent, Instant now);
        CleanupCandidate cleanupTarget(String fileId);
    }

    private final String listPrefix;
    @javax.ejb.EJB private AttachmentDatabaseService service;
    @javax.inject.Inject private AttachmentConfiguration configuration;
    private CleanupTransactions db;
    private PrivateObjectStore store;

    public AttachmentReconciler() {
        this.listPrefix = OBJECT_KEY_ROOT;
    }

    AttachmentReconciler(CleanupTransactions db, PrivateObjectStore store, String listPrefix) {
        this.db = Objects.requireNonNull(db);
        this.store = Objects.requireNonNull(store);
        this.listPrefix = Objects.requireNonNull(listPrefix);
    }

    @Schedule(hour = "*", minute = "*", second = "0", persistent = false)
    public void scheduledSweep(Timer timer) {
        runBatch(Instant.now());
    }

    /** Delegates to the container-managed service; each call is its own transaction. */
    private static final class ServiceCleanupTransactions implements CleanupTransactions {
        private final AttachmentDatabaseService service;
        ServiceCleanupTransactions(AttachmentDatabaseService service) { this.service = service; }
        public List<CleanupCandidate> claimCleanupBatch(Instant now, int limit) { return service.claimCleanupBatch(now, limit); }
        public void recordCleanup(String fileId, long expectedVersion, boolean confirmedAbsent, Instant now) { service.recordCleanup(fileId, expectedVersion, confirmedAbsent, now); }
        public CleanupCandidate cleanupTarget(String fileId) { return service.cleanupTarget(fileId); }
    }

    public int runBatch(Instant now) {
        List<CleanupCandidate> batch = db().claimCleanupBatch(now, BATCH_LIMIT);
        Set<String> claimedKeys = new HashSet<>();
        for (CleanupCandidate candidate : batch) {
            claimedKeys.add(candidate.getObjectKey());
            reconcile(candidate, now);
        }
        int unverified = sweep(claimedKeys);
        if (unverified > 0) {
            LOG.warning("attachmentCleanupReview type=UNVERIFIED_OBJECT_KEYS count=" + unverified);
        }
        return batch.size();
    }

    private void reconcile(CleanupCandidate candidate, Instant now) {
        try {
            if (store().head(candidate.getObjectKey()) != null) store().delete(candidate.getObjectKey());
            db().recordCleanup(candidate.getFileId(), candidate.getVersion(), true, now);
        } catch (RuntimeException cleanupFailure) {
            try { db().recordCleanup(candidate.getFileId(), candidate.getVersion(), false, now); }
            catch (RuntimeException ignored) { }
        }
    }

    /**
     * Bounded sweep of the controlled prefix. Only keys proven to belong to a terminal
     * intent may be cleaned; anything unregistered is counted for review and kept.
     */
    private int sweep(Set<String> claimedKeys) {
        int unverified = 0;
        String afterKey = null;
        for (int page = 0; page < SWEEP_PAGES; page++) {
            List<String> keys = store().listKeys(listPrefix, afterKey, BATCH_LIMIT);
            if (keys == null || keys.isEmpty()) break;
            for (String key : keys) {
                if (claimedKeys.contains(key)) continue; // already reconciled in this batch
                CleanupCandidate intent = ownedIntent(key);
                if (intent == null) {
                    unverified++;
                } else if (terminal(intent.getState())) {
                    try {
                        if (store().head(key) != null) store().delete(key);
                    } catch (RuntimeException ignored) { }
                }
            }
            afterKey = keys.get(keys.size() - 1);
            if (keys.size() < BATCH_LIMIT) break;
        }
        return unverified;
    }

    private CleanupCandidate ownedIntent(String objectKey) {
        String fileId = fileIdOf(objectKey);
        if (fileId == null) return null;
        CleanupCandidate intent;
        try { intent = db().cleanupTarget(fileId); }
        catch (RuntimeException unreadable) { return null; }
        return intent != null && objectKey.equals(intent.getObjectKey()) ? intent : null;
    }

    private static boolean terminal(AttachmentState state) {
        return state == AttachmentState.ABORTED || state == AttachmentState.DELETE_PENDING || state == AttachmentState.DELETED;
    }

    private static String fileIdOf(String objectKey) {
        if (objectKey == null || !objectKey.startsWith(OBJECT_KEY_ROOT)) return null;
        String rest = objectKey.substring(OBJECT_KEY_ROOT.length());
        int slash = rest.indexOf('/');
        if (slash < 1 || slash == rest.length() - 1) return null;
        String fileId = rest.substring(0, slash);
        return fileId.matches("[0-9a-fA-F-]{36}") ? fileId : null;
    }

    private CleanupTransactions db() {
        if (db == null) db = new ServiceCleanupTransactions(service);
        return db;
    }

    private PrivateObjectStore store() {
        if (store == null) store = configuration.createStore();
        return store;
    }
}
