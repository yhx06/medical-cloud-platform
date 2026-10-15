package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.consultation.ClinicalRecord;
import javax.ejb.*;
import javax.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Short database transactions only. Network/storage work belongs to the coordinator.
 * The attachment datasource must use REPEATABLE_READ (deployment gate); REQUIRED alone
 * does not supply a consistent count/rows view.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class AttachmentDatabaseService {
    @PersistenceContext(unitName="medicalCloudCourse") private EntityManager manager;
    @EJB private AuditWritePort journal;
    @javax.inject.Inject private AttachmentLimits limits;
    public AttachmentDatabaseService() { }
    public AttachmentDatabaseService(EntityManager manager) { this(manager,new AttachmentLimits()); }
    public AttachmentDatabaseService(EntityManager manager,AttachmentLimits limits) { this.manager=Objects.requireNonNull(manager); this.journal=new AuditJournal(manager); this.limits=Objects.requireNonNull(limits); }
    private AttachmentAccessPolicy policy() { return new AttachmentAccessPolicy(manager); }

    public UploadOperationView preparePatient(CurrentActor actor,String appointmentId,PrepareUploadRequest request,Instant now) {
        if(actor==null || actor.getRole()!=Role.PATIENT) { policy().account(actor); throw AttachmentAccessPolicy.forbidden(); }
        return prepare(actor,appointmentId,null,AttachmentSourceType.PATIENT_APPOINTMENT,request,now);
    }
    public UploadOperationView prepareDoctor(CurrentActor actor,String recordId,PrepareUploadRequest request,Instant now) {
        Account account=policy().account(actor);
        if(account.getRole()!=Role.DOCTOR) throw AttachmentAccessPolicy.forbidden();
        ClinicalRecord record=policy().fresh(ClinicalRecord.class,recordId,null);
        return prepare(actor,record.getAppointmentId(),recordId,AttachmentSourceType.DOCTOR_RECORD,request,now);
    }
    private UploadOperationView prepare(CurrentActor actor,String appointmentId,String recordId,AttachmentSourceType source,PrepareUploadRequest raw,Instant now) {
        AttachmentAccessPolicy p=policy();
        Appointment appointment=p.locked(actor,appointmentId,actor.getUserId(),LockModeType.PESSIMISTIC_WRITE,true);
        p.writableSource(appointment,source,recordId,actor);
        PrepareUploadRequest request=AttachmentDescriptors.requireValid(raw,limits);
        // The uploader account lock covers all appointments, including idempotency and quota.
        List<FileMetadata> previous=manager.createQuery("select f from FileMetadata f where f.uploaderId=:uploader and f.uploadKey=:key",FileMetadata.class)
            .setParameter("uploader",actor.getUserId()).setParameter("key",request.getUploadKey()).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if(!previous.isEmpty()) {
            FileMetadata f=p.fresh(FileMetadata.class,previous.get(0).id,LockModeType.PESSIMISTIC_WRITE);
            if(!appointmentId.equals(f.appointmentId) || !Objects.equals(recordId,f.recordId) || source!=f.sourceType || !request.getFileName().equals(f.fileName) || !request.getContentType().equals(f.contentType) || request.getSizeBytes()!=f.sizeBytes || !request.getSha256().equals(f.sha256)) throw new AttachmentException(409,"UPLOAD_CONFLICT");
            p.file(appointment,f);
            return f.operation();
        }
        // Locking/current read avoids an earlier repeatable-read snapshot missing a just committed reservation.
        List<FileMetadata> pending=manager.createQuery("select f from FileMetadata f where f.uploaderId=:uploader and f.state in (:reserved,:uploading)",FileMetadata.class)
            .setParameter("uploader",actor.getUserId()).setParameter("reserved",AttachmentState.RESERVED).setParameter("uploading",AttachmentState.UPLOADING)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if(pending.size()>=limits.getUploaderPendingLimit()) throw new AttachmentException(429,"FILE_LIMIT_REACHED");
        FileMetadata f=new FileMetadata(); f.id=UUID.randomUUID().toString(); f.tenantId=appointment.getTenantId(); f.appointmentId=appointmentId;
        f.patientId=appointment.getPatientId(); f.doctorId=appointment.getDoctorId(); f.uploaderId=actor.getUserId(); f.recordId=recordId; f.sourceType=source;
        f.fileName=request.getFileName(); f.contentType=request.getContentType(); f.sizeBytes=request.getSizeBytes(); f.sha256=request.getSha256(); f.uploadKey=request.getUploadKey();
        f.objectKey="course-attachments/"+f.id+"/"+UUID.randomUUID(); f.state=AttachmentState.RESERVED; f.createdAt=stamp(now); f.updatedAt=stamp(now); f.leaseUntil=stamp(now.plusSeconds(900));
        manager.persist(f); manager.flush(); return f.operation();
    }
    public UploadAttempt claim(CurrentActor actor,String uploadId,Instant now,AuditContext context) {
        FileMetadata f=lockedFile(actor,uploadId,LockModeType.PESSIMISTIC_WRITE,false,true);
        if(f.state==AttachmentState.AVAILABLE) {
            audit(actor,f,context,AuditEventType.FILE_UPLOAD,AuditResult.NO_CHANGE,now);
            return attempt(f);
        }
        requireWritable(actor,f);
        if(f.state==AttachmentState.UPLOADING && live(f,now)) throw new AttachmentException(409,"UPLOAD_IN_PROGRESS");
        if(f.state!=AttachmentState.RESERVED || !live(f,now)) throw AttachmentAccessPolicy.conflict();
        f.state=AttachmentState.UPLOADING; f.attemptToken=UUID.randomUUID().toString(); f.leaseUntil=stamp(now.plusSeconds(600)); f.updatedAt=stamp(now);
        manager.flush(); return attempt(f);
    }
    public AttachmentView publish(CurrentActor actor,String uploadId,String attemptToken,ValidatedAttachment content,Instant now,AuditContext context) {
        FileMetadata f=lockedFile(actor,uploadId,LockModeType.PESSIMISTIC_WRITE,false,true);
        if(!Objects.equals(f.attemptToken,attemptToken) || attemptToken==null) throw AttachmentAccessPolicy.conflict();
        if(content==null || !f.fileName.equals(content.getFileName()) || !f.contentType.equals(content.getContentType()) || f.sizeBytes!=content.getSizeBytes() || !f.sha256.equals(content.getSha256())) throw new AttachmentException(400,"FILE_INVALID");
        if(f.state==AttachmentState.AVAILABLE) { audit(actor,f,context,AuditEventType.FILE_UPLOAD,AuditResult.NO_CHANGE,now); return f.view(); }
        requireWritable(actor,f);
        if(f.state!=AttachmentState.UPLOADING || !live(f,now)) throw AttachmentAccessPolicy.conflict();
        f.state=AttachmentState.AVAILABLE; f.updatedAt=stamp(now); f.leaseUntil=null;
        audit(actor,f,context,AuditEventType.FILE_UPLOAD,AuditResult.SUCCESS,now); return f.view();
    }
    public UploadOperationView operation(CurrentActor actor,String uploadId) { return readableFile(actor,uploadId,true).operation(); }
    public UploadOperationView resolveUpload(CurrentActor actor,String uploadId,String attemptToken) {
        FileMetadata f=readableFile(actor,uploadId,true);
        if(attemptToken==null || !attemptToken.equals(f.attemptToken)) throw AttachmentAccessPolicy.conflict();
        return f.operation();
    }
    public AttachmentPage listForPatient(CurrentActor actor,String appointmentId,String rawPage,String rawPageSize) { return list(actor,Role.PATIENT,appointmentId,rawPage,rawPageSize); }
    public AttachmentPage listForDoctor(CurrentActor actor,String appointmentId,String rawPage,String rawPageSize) { return list(actor,Role.DOCTOR,appointmentId,rawPage,rawPageSize); }
    private AttachmentPage list(CurrentActor actor,Role role,String appointmentId,String rawPage,String rawPageSize) {
        Appointment a=policy().readable(actor,appointmentId);
        if(actor.getRole()!=role) throw AttachmentAccessPolicy.forbidden();
        int page=positive(rawPage,1,Integer.MAX_VALUE), size=positive(rawPageSize,20,100);
        long offset=(long)(page-1)*size; if(offset>Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid pagination");
        String filter=" from FileMetadata f where f.appointmentId=:appointment and f.tenantId=:tenant and f.patientId=:patient and f.doctorId=:doctor and f.state=:state";
        TypedQuery<Long> count=manager.createQuery("select count(f)"+filter,Long.class);
        bind(count,a); long total=count.getSingleResult();
        TypedQuery<FileMetadata> rows=manager.createQuery("select f"+filter+" order by f.createdAt desc, f.id desc",FileMetadata.class);
        bind(rows,a); List<AttachmentView> items=new ArrayList<>();
        for(FileMetadata f:rows.setFirstResult((int)offset).setMaxResults(size).getResultList()) { policy().file(a,f); items.add(f.view()); }
        return new AttachmentPage(items,total,page,size);
    }
    private void bind(Query query,Appointment a) { query.setParameter("appointment",a.getId()).setParameter("tenant",a.getTenantId()).setParameter("patient",a.getPatientId()).setParameter("doctor",a.getDoctorId()).setParameter("state",AttachmentState.AVAILABLE); }
    private static int positive(String raw,int fallback,int max) { if(raw==null) return fallback; if(!raw.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Invalid pagination"); try { int n=Integer.parseInt(raw); if(n>max) throw new IllegalArgumentException("Invalid pagination"); return n; } catch(NumberFormatException ex) { throw new IllegalArgumentException("Invalid pagination"); } }
    public AttachmentDeleteView logicalDelete(CurrentActor actor,String fileId,Instant now,AuditContext context) {
        FileMetadata f=lockedFile(actor,fileId,LockModeType.PESSIMISTIC_WRITE,false,true);
        if(actor.getRole()!=Role.PATIENT || f.sourceType!=AttachmentSourceType.PATIENT_APPOINTMENT) throw AttachmentAccessPolicy.forbidden();
        if(f.state==AttachmentState.DELETE_PENDING || f.state==AttachmentState.DELETED) { audit(actor,f,context,AuditEventType.FILE_DELETE,AuditResult.NO_CHANGE,now); return new AttachmentDeleteView(f.id,f.state); }
        Appointment a=requireWritable(actor,f);
        if(f.state!=AttachmentState.AVAILABLE || !"BOOKED".equals(a.getStatus()) || a.getStartedAt()!=null) throw AttachmentAccessPolicy.conflict();
        f.state=AttachmentState.DELETE_PENDING; f.deletedAt=stamp(now); f.updatedAt=stamp(now); f.nextRetryAt=stamp(now); f.retryCount=0;
        audit(actor,f,context,AuditEventType.FILE_DELETE,AuditResult.SUCCESS,now); return new AttachmentDeleteView(f.id,f.state);
    }
    public AttachmentSnapshot downloadCandidate(CurrentActor actor,String fileId) {
        FileMetadata f=readableFile(actor,fileId,false); if(f.state!=AttachmentState.AVAILABLE) throw AttachmentAccessPolicy.conflict();
        return new AttachmentSnapshot(f.id,f.objectKey,f.fileName,f.contentType,f.version);
    }
    public void commitDownloadAuthorization(CurrentActor actor,String fileId,long expectedVersion,Instant expiresAt,Instant now,AuditContext context) {
        FileMetadata f=lockedFile(actor,fileId,LockModeType.PESSIMISTIC_READ,false,false);
        if(f.state!=AttachmentState.AVAILABLE || f.version!=expectedVersion || expiresAt==null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(300))) throw AttachmentAccessPolicy.conflict();
        audit(actor,f,context,AuditEventType.FILE_DOWNLOAD_AUTHORIZE,AuditResult.SUCCESS,now);
    }
    public boolean abortAttempt(String uploadId,String attemptToken,Instant now) {
        FileMetadata f=manager.find(FileMetadata.class,uploadId,LockModeType.PESSIMISTIC_WRITE);
        if(f==null) return false; manager.refresh(f,LockModeType.PESSIMISTIC_WRITE);
        if(f.state!=AttachmentState.UPLOADING || attemptToken==null || !attemptToken.equals(f.attemptToken)) return false;
        abort(f,now); manager.flush(); return true;
    }
    public List<CleanupCandidate> claimCleanupBatch(Instant now,int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Invalid cleanup batch");
        List<FileMetadata> rows=manager.createQuery("select f from FileMetadata f where (f.state in (:reserved,:uploading) and f.leaseUntil<=:now) or (f.state in (:pending,:deleted,:aborted) and (f.nextRetryAt is null or f.nextRetryAt<=:now)) order by f.id",FileMetadata.class)
            .setParameter("reserved",AttachmentState.RESERVED).setParameter("uploading",AttachmentState.UPLOADING).setParameter("pending",AttachmentState.DELETE_PENDING).setParameter("deleted",AttachmentState.DELETED).setParameter("aborted",AttachmentState.ABORTED).setParameter("now",stamp(now))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE).setMaxResults(limit).getResultList();
        List<FileMetadata> claimed=new ArrayList<>();
        for(FileMetadata f:rows) {
            manager.refresh(f,LockModeType.PESSIMISTIC_WRITE);
            if((f.state==AttachmentState.RESERVED || f.state==AttachmentState.UPLOADING) && !live(f,now)) abort(f,now);
            if(terminal(f) && (f.nextRetryAt==null || !f.nextRetryAt.toInstant().isAfter(now))) { f.nextRetryAt=stamp(now.plusSeconds(600)); f.updatedAt=stamp(now); claimed.add(f); }
        }
        manager.flush(); List<CleanupCandidate> results=new ArrayList<>();
        for(FileMetadata f:claimed) results.add(new CleanupCandidate(f.id,f.objectKey,f.state,f.version));
        return results;
    }
    public void recordCleanup(String fileId,long expectedVersion,boolean confirmedAbsent,Instant now) {
        FileMetadata f=manager.find(FileMetadata.class,fileId,LockModeType.PESSIMISTIC_WRITE);
        if(f==null) return; manager.refresh(f,LockModeType.PESSIMISTIC_WRITE);
        if(f.version!=expectedVersion || !terminal(f)) return;
        if(confirmedAbsent) { if(f.state==AttachmentState.DELETE_PENDING) f.state=AttachmentState.DELETED; f.retryCount=0; f.nextRetryAt=stamp(now.plusSeconds(1800)); }
        else { f.retryCount=Math.min(31,f.retryCount+1); long minutes=f.retryCount>=6?30:1L<<(f.retryCount-1); f.nextRetryAt=stamp(now.plusSeconds(minutes*60)); }
        f.updatedAt=stamp(now); manager.flush();
    }
    private boolean terminal(FileMetadata f) { return f.state==AttachmentState.ABORTED || f.state==AttachmentState.DELETE_PENDING || f.state==AttachmentState.DELETED; }
    private void abort(FileMetadata f,Instant now) { f.state=AttachmentState.ABORTED; f.updatedAt=stamp(now); f.leaseUntil=null; f.nextRetryAt=stamp(now); }
    private FileMetadata readableFile(CurrentActor actor,String id,boolean uploaderOnly) {
        policy().account(actor); FileMetadata f=policy().fresh(FileMetadata.class,id,null);
        Appointment a=policy().readable(actor,f.appointmentId); policy().file(a,f);
        if(uploaderOnly && !actor.getUserId().equals(f.uploaderId)) throw AttachmentAccessPolicy.forbidden(); return f;
    }
    private FileMetadata lockedFile(CurrentActor actor,String id,LockModeType lock,boolean writable,boolean uploaderOnly) {
        FileMetadata candidate=readableFile(actor,id,uploaderOnly); String appointmentId=candidate.appointmentId, uploader=candidate.uploaderId;
        // A parent-lock wait may let the row version advance; reacquire it as a fresh managed instance.
        manager.detach(candidate);
        Appointment a=policy().locked(actor,appointmentId,uploader,lock,writable);
        FileMetadata f=policy().fresh(FileMetadata.class,id,lock); policy().file(a,f);
        if(!uploader.equals(f.uploaderId) || uploaderOnly && !actor.getUserId().equals(f.uploaderId)) throw AttachmentAccessPolicy.forbidden(); return f;
    }
    private Appointment requireWritable(CurrentActor actor,FileMetadata f) {
        // Parent locks are already held; use only refreshed state, never acquire parents after the file lock.
        Appointment a=manager.find(Appointment.class,f.appointmentId);
        com.medcloud.course.paas.tenant.Tenant tenant=manager.find(com.medcloud.course.paas.tenant.Tenant.class,f.tenantId);
        if(!"ACTIVE".equals(tenant.getStatus())) throw AttachmentAccessPolicy.conflict();
        policy().writableSource(a,f.sourceType,f.recordId,actor); return a;
    }
    private void audit(CurrentActor actor,FileMetadata f,AuditContext context,AuditEventType event,AuditResult result,Instant now) {
        if(context==null || context.getEventType()!=event) throw new IllegalArgumentException("Audit event mismatch");
        Account account=manager.find(Account.class,actor.getUserId()); context.attachActor(new CurrentActor(account.getId(),account.getRole(),account.getTenantId())); context.attachVerifiedTenant(f.tenantId);
        journal.append(context,f.id,result,now);
    }
    private UploadAttempt attempt(FileMetadata f) { return new UploadAttempt(f.id,f.state,f.attemptToken,f.objectKey,f.fileName,f.contentType,f.sizeBytes,f.sha256,f.leaseUntil==null?null:f.leaseUntil.toInstant(),f.state==AttachmentState.AVAILABLE?f.view():null); }
    private static Timestamp stamp(Instant now) { return Timestamp.from(Objects.requireNonNull(now)); }
    private static boolean live(FileMetadata f,Instant now) { return f.leaseUntil!=null && f.leaseUntil.toInstant().isAfter(now); }
}
