package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Attachment orchestration outside any JTA transaction. Claims, publishes and audits are
 * short database transactions; receive/validate/PUT/HEAD/sign and their waits never are.
 * Unknown commit outcomes are resolved by a new state read — never by guessing a delete.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class AttachmentCoordinator {

    /** Short database calls only; the container's REQUIRED service supplies them in production. */
    interface AttachmentTransactions {
        UploadAttempt claim(CurrentActor actor, String uploadId, Instant now, AuditContext context);
        AttachmentView publish(CurrentActor actor, String uploadId, String attemptToken, ValidatedAttachment content, Instant now, AuditContext context);
        boolean abortAttempt(String uploadId, String attemptToken, Instant now);
        AttachmentSnapshot downloadCandidate(CurrentActor actor, String fileId);
        void commitDownloadAuthorization(CurrentActor actor, String fileId, long expectedVersion, Instant expiresAt, Instant now, AuditContext context);
        AttachmentDeleteView logicalDelete(CurrentActor actor, String fileId, Instant now, AuditContext context);
        CleanupCandidate cleanupTarget(String fileId);
        void recordCleanup(String fileId, long expectedVersion, boolean confirmedAbsent, Instant now);
        UploadOperationView operation(CurrentActor actor, String uploadId);
    }

    /** Re-authentication after storage waits; the same bearer token must still resolve. */
    interface TokenAuthenticator { CurrentActor authenticate(String bearerToken); }

    /** Independent failure audit; only for outcomes already known not-succeeded. */
    interface FailureSink { void record(AuditContext context, CurrentActor actor, AuditReasonCode reason); }

    @EJB private AttachmentDatabaseService service;
    @EJB private AuthService authenticator;
    @EJB private AttachmentFailureRecorder failureRecorder;
    @Inject private AttachmentConfiguration configuration;
    @Inject private AttachmentLimits limits;
    @Inject private AttachmentValidator validator;

    private AttachmentTransactions db;
    private TokenAuthenticator auth;
    private PrivateObjectStore store;
    private AttachmentValidator contentValidator;
    private AttachmentLimits attachmentLimits;
    private Path tempDirectory;
    private FailureSink failures;
    private Clock clock;

    public AttachmentCoordinator() { }

    AttachmentCoordinator(AttachmentTransactions db, TokenAuthenticator auth, PrivateObjectStore store,
                          AttachmentValidator validator, AttachmentLimits limits, Path tempDirectory,
                          FailureSink failures, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.auth = Objects.requireNonNull(auth);
        this.store = Objects.requireNonNull(store);
        this.contentValidator = Objects.requireNonNull(validator);
        this.attachmentLimits = Objects.requireNonNull(limits);
        this.tempDirectory = Objects.requireNonNull(tempDirectory);
        this.failures = Objects.requireNonNull(failures);
        this.clock = Objects.requireNonNull(clock);
    }

    private AttachmentTransactions db() {
        if (db == null) db = new ServiceTransactions(service);
        return db;
    }

    private TokenAuthenticator auth() {
        if (auth == null) auth = authenticator::authenticate;
        return auth;
    }

    private PrivateObjectStore store() {
        if (store == null) store = configuration.createStore();
        return store;
    }

    private AttachmentValidator validator() {
        if (contentValidator == null) contentValidator = validator;
        return contentValidator;
    }

    private AttachmentLimits limits() {
        if (attachmentLimits == null) attachmentLimits = limits;
        return attachmentLimits;
    }

    private Path tempDirectory() {
        if (tempDirectory == null) tempDirectory = configuration.getTempDirectory();
        return tempDirectory;
    }

    private FailureSink failures() {
        if (failures == null) failures = failureRecorder::record;
        return failures;
    }

    private Clock clock() {
        if (clock == null) clock = Clock.systemUTC();
        return clock;
    }

    public UploadOperationView upload(String bearerToken, String uploadId, InputStream content, Long contentLength, AuditContext context) {
        CurrentActor actor = auth().authenticate(bearerToken);
        UploadAttempt attempt = db().claim(actor, uploadId, clock().instant(), context);
        if (attempt.getAvailableFile() != null) return new UploadOperationView(uploadId, attempt.getState(), attempt.getAvailableFile());
        ValidatedAttachment validated = null;
        boolean published = false;
        try {
            validated = validator().validate(content, contentLength, descriptorFrom(attempt), limits(), tempDirectory());
            try {
                store().put(attempt.getObjectKey(), validated);
            } catch (RuntimeException storeFailure) {
                throw storeFailure instanceof AttachmentException ? storeFailure : new AttachmentException(503, "OBJECT_STORE_UNAVAILABLE");
            }
            CurrentActor fresh = auth().authenticate(bearerToken);
            db().publish(fresh, uploadId, attempt.getAttemptToken(), validated, clock().instant(), context);
            published = true;
            return db().operation(fresh, uploadId);
        } catch (RuntimeException failure) {
            if (published) throw failure;
            return recoverUpload(actor, attempt, failure, context);
        } finally {
            if (validated != null && validated.getPath() != null) {
                try { Files.deleteIfExists(validated.getPath()); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Resolve a failed upload attempt. A fenced abort followed by the compensating delete
     * only happens when the database confirms the attempt is over; an unreadable database
     * reports an unknown outcome and never deletes.
     */
    private UploadOperationView recoverUpload(CurrentActor actor, UploadAttempt attempt, RuntimeException failure, AuditContext context) {
        Boolean aborted;
        try {
            aborted = db().abortAttempt(attempt.getUploadId(), attempt.getAttemptToken(), clock().instant());
        } catch (RuntimeException databaseUnreadable) {
            throw new AttachmentException(500, "INTERNAL_ERROR");
        }
        if (Boolean.FALSE.equals(aborted)) {
            UploadOperationView current;
            try { current = db().operation(actor, attempt.getUploadId()); }
            catch (RuntimeException databaseUnreadable) { throw new AttachmentException(500, "INTERNAL_ERROR"); }
            if (current.getState() == AttachmentState.AVAILABLE) return current;
        }
        try { store().delete(attempt.getObjectKey()); } catch (RuntimeException ignored) { }
        recordFailure(context, actor, failure);
        throw mapFailure(failure);
    }

    public AttachmentDeleteView deletePatient(String bearerToken, String fileId, AuditContext context) {
        CurrentActor actor = auth().authenticate(bearerToken);
        Instant now = clock().instant();
        AttachmentDeleteView view = db().logicalDelete(actor, fileId, now, context);
        if (view.getState() != AttachmentState.DELETE_PENDING) return view;
        try {
            CleanupCandidate target = db().cleanupTarget(fileId);
            if (target == null) return view;
            store().delete(target.getObjectKey());
            db().recordCleanup(target.getFileId(), target.getVersion(), true, now);
            return new AttachmentDeleteView(fileId, AttachmentState.DELETED);
        } catch (RuntimeException cleanupFailure) {
            // Physical cleanup is not the business result: the logical delete already committed.
            return new AttachmentDeleteView(fileId, AttachmentState.DELETE_PENDING);
        }
    }

    public DownloadAuthorizationView authorizeDownload(String bearerToken, String fileId, AuditContext context) {
        CurrentActor actor = auth().authenticate(bearerToken);
        Instant issuedAt = clock().instant();
        AttachmentSnapshot candidate = db().downloadCandidate(actor, fileId);
        try {
            if (store().head(candidate.getObjectKey()) == null) throw new AttachmentException(409, "FILE_STATE_CONFLICT");
            StoreDownload signed = store().signGet(candidate, issuedAt, 300);
            CurrentActor fresh = auth().authenticate(bearerToken);
            db().commitDownloadAuthorization(fresh, fileId, candidate.getVersion(), signed.getExpiresAt(), clock().instant(), context);
            return new DownloadAuthorizationView(fileId, signed.getUrl(), signed.getExpiresAt().toString());
        } catch (RuntimeException failure) {
            recordFailure(context, actor, failure);
            throw mapFailure(failure);
        }
    }

    private void recordFailure(AuditContext context, CurrentActor actor, RuntimeException failure) {
        try { failures().record(context, actor, reasonFor(failure)); }
        catch (RuntimeException ignored) { }
    }

    private static PrepareUploadRequest descriptorFrom(UploadAttempt attempt) {
        return new PrepareUploadRequest(attempt.getUploadId(), attempt.getFileName(), attempt.getContentType(),
            attempt.getSizeBytes(), attempt.getSha256());
    }

    private static RuntimeException mapFailure(RuntimeException failure) {
        if (failure instanceof AttachmentException) return failure;
        if (failure instanceof SecurityException) return new AttachmentException(401, "UNAUTHORIZED");
        return new AttachmentException(500, "INTERNAL_ERROR");
    }

    private static AuditReasonCode reasonFor(RuntimeException failure) {
        if (failure instanceof AttachmentException) {
            int status = ((AttachmentException) failure).getStatus();
            if (status == 401) return AuditReasonCode.UNAUTHORIZED;
            if (status == 403) return AuditReasonCode.FORBIDDEN;
            if (status == 400 || status == 413 || status == 415) return AuditReasonCode.BAD_REQUEST;
            if (status == 409 || status == 429) return AuditReasonCode.CONFLICT;
            return AuditReasonCode.INTERNAL_ERROR;
        }
        return ApiFailureMapper.reasonFor(failure);
    }

    /** Delegates to the container-managed service; each call is its own transaction. */
    private static final class ServiceTransactions implements AttachmentTransactions {
        private final AttachmentDatabaseService service;
        ServiceTransactions(AttachmentDatabaseService service) { this.service = service; }
        public UploadAttempt claim(CurrentActor actor, String uploadId, Instant now, AuditContext context) { return service.claim(actor, uploadId, now, context); }
        public AttachmentView publish(CurrentActor actor, String uploadId, String attemptToken, ValidatedAttachment content, Instant now, AuditContext context) { return service.publish(actor, uploadId, attemptToken, content, now, context); }
        public boolean abortAttempt(String uploadId, String attemptToken, Instant now) { return service.abortAttempt(uploadId, attemptToken, now); }
        public AttachmentSnapshot downloadCandidate(CurrentActor actor, String fileId) { return service.downloadCandidate(actor, fileId); }
        public void commitDownloadAuthorization(CurrentActor actor, String fileId, long expectedVersion, Instant expiresAt, Instant now, AuditContext context) { service.commitDownloadAuthorization(actor, fileId, expectedVersion, expiresAt, now, context); }
        public AttachmentDeleteView logicalDelete(CurrentActor actor, String fileId, Instant now, AuditContext context) { return service.logicalDelete(actor, fileId, now, context); }
        public CleanupCandidate cleanupTarget(String fileId) { return service.cleanupTarget(fileId); }
        public void recordCleanup(String fileId, long expectedVersion, boolean confirmedAbsent, Instant now) { service.recordCleanup(fileId, expectedVersion, confirmedAbsent, now); }
        public UploadOperationView operation(CurrentActor actor, String uploadId) { return service.operation(actor, uploadId); }
    }
}
