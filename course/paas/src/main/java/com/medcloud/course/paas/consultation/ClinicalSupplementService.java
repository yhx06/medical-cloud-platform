package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.TenantAccessPolicy;
import javax.ejb.*;
import javax.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Stateless
public class ClinicalSupplementService {
    @PersistenceContext(unitName="medicalCloudCourse") private EntityManager manager;
    @EJB private AuditWritePort journal;
    public ClinicalSupplementService(){ }
    public ClinicalSupplementService(EntityManager manager){this.manager=manager;journal=new AuditJournal(manager);}
    private ClinicalAccessPolicy access(){return new ClinicalAccessPolicy(manager);}

    public PatientComplaintView complaint(CurrentActor actor,String appointmentId) {
        return complaintView(access().patientAppointment(actor,appointmentId));
    }
    public PatientComplaintView updateComplaint(CurrentActor actor,String appointmentId,SupplementComplaintRequest request,AuditContext context) {
        return updateComplaint(actor,appointmentId,request,Instant.now(),context);
    }
    PatientComplaintView updateComplaint(CurrentActor actor,String appointmentId,SupplementComplaintRequest request,Instant now,AuditContext context) {
        Appointment a=lockPatient(actor,appointmentId,context);
        if(!"BOOKED".equals(a.getStatus())&&!"IN_PROGRESS".equals(a.getStatus()))throw conflict("STATE");
        if(request==null||now==null)throw invalid();
        long expected=ClinicalContentRules.requireRevision(request.getExpectedRevision());
        String description=ClinicalContentRules.requireDescription(request.getDescription());
        if(expected!=a.getComplaintRevision())throw conflict("REVISION");
        boolean same=Objects.equals(description,a.getPatientComplaint());
        if(!same)a.updatePatientComplaint(description,now);
        journal.append(context,a.getId(),same?AuditResult.NO_CHANGE:AuditResult.SUCCESS,now);
        return complaintView(a);
    }
    private PatientComplaintView complaintView(Appointment a) {
        boolean writable=("BOOKED".equals(a.getStatus())||"IN_PROGRESS".equals(a.getStatus()))
                &&"ACTIVE".equals(new TenantAccessPolicy(manager).readableTenant(a.getTenantId()).getStatus());
        return new PatientComplaintView(a.getId(),a.getPatientComplaint(),format(a.getComplaintUpdatedAt()),a.getComplaintRevision(),writable);
    }
    public ClinicalWorkspaceView workspace(CurrentActor actor,String appointmentId) {
        Appointment a=access().doctorAppointment(actor,appointmentId);
        ClinicalDraft draft=draft(a,false);
        List<ClinicalRecord> records=manager.createQuery("select r from ClinicalRecord r where r.appointmentId=:id",ClinicalRecord.class).setParameter("id",appointmentId).getResultList();
        ClinicalRecord record=records.isEmpty()?null:records.get(0);
        if(record!=null)access().verifyRecord(record,a);
        return new ClinicalWorkspaceView(a.getId(),a.getStatus(),complaintView(a),draft==null?null:draftView(draft),record==null?null:record.getId(),draft==null?0:draft.getRevision());
    }
    public ClinicalDraftView saveDraft(CurrentActor actor,String appointmentId,SaveClinicalDraftRequest request,AuditContext context) {
        return saveDraft(actor,appointmentId,request,Instant.now(),context);
    }
    ClinicalDraftView saveDraft(CurrentActor actor,String appointmentId,SaveClinicalDraftRequest request,Instant now,AuditContext context) {
        Appointment a=lockDoctor(actor,appointmentId,context);
        if(!"IN_PROGRESS".equals(a.getStatus()))throw conflict("STATE");
        if(request==null||now==null)throw invalid();
        long expected=ClinicalContentRules.requireRevision(request.getExpectedRevision());
        ClinicalContentView content=ClinicalContentRules.requireDraft(new ClinicalContentView(request.getComplaint(),request.getDiagnosis(),request.getAdvice(),request.getNotes()));
        ClinicalDraft draft=draft(a,true);
        if(expected!=(draft==null?0:draft.getRevision()))throw conflict("REVISION");
        boolean same=draft!=null&&same(content,draft.content());
        if(draft==null){draft=new ClinicalDraft(a,content,now);manager.persist(draft);}
        else if(!same)draft.update(content,now);
        journal.append(context,draft.getId(),same?AuditResult.NO_CHANGE:AuditResult.SUCCESS,now);
        return draftView(draft);
    }
    private ClinicalDraft draft(Appointment a,boolean locked) {
        TypedQuery<ClinicalDraft> query=manager.createQuery("select d from ClinicalDraft d where d.appointmentId=:id",ClinicalDraft.class).setParameter("id",a.getId());
        if(locked)query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        List<ClinicalDraft> rows=query.getResultList();
        ClinicalDraft draft=rows.isEmpty()?null:rows.get(0);if(draft!=null){if(locked)manager.refresh(draft,LockModeType.PESSIMISTIC_WRITE);else manager.refresh(draft);}
        access().verifyDraft(draft,a);return draft;
    }
    private ClinicalDraftView draftView(ClinicalDraft d){return new ClinicalDraftView(d.content(),d.getRevision(),format(d.getSavedAt()));}
    public ClinicalRecordDetailsView doctorDetails(CurrentActor actor,String recordId){return details(access().doctorRecord(actor,recordId));}
    public ClinicalRecordDetailsView patientDetails(CurrentActor actor,String appointmentId){return details(access().patientRecord(actor,appointmentId));}
    private ClinicalRecordDetailsView details(ClinicalRecord record) {
        RecordCorrection latest=latest(record,false);ClinicalContentView original=original(record);
        return new ClinicalRecordDetailsView(record.getId(),record.getAppointmentId(),original,latest==null?original:latest.after(),latest==null?0:latest.getSequence(),format(record.getCompletedAt()));
    }
    public RecordCorrectionPage correctionsForDoctor(CurrentActor actor,String recordId,String page,String pageSize) {
        return history(access().doctorRecord(actor,recordId),page,pageSize);
    }
    public RecordCorrectionPage correctionsForPatient(CurrentActor actor,String appointmentId,String page,String pageSize) {
        return history(access().patientRecord(actor,appointmentId),page,pageSize);
    }
    private RecordCorrectionPage history(ClinicalRecord record,String pageText,String sizeText) {
        // An append must hold the parent write lock; the read lock keeps count and page in one view.
        Appointment a=manager.find(Appointment.class,record.getAppointmentId(),LockModeType.PESSIMISTIC_READ);
        manager.refresh(a,LockModeType.PESSIMISTIC_READ);access().verifyRecord(record,a);
        int page=number(pageText,1,Integer.MAX_VALUE),size=number(sizeText,5,5);
        long offset=((long)page-1)*size;if(offset>Integer.MAX_VALUE)throw invalid();
        long total=manager.createQuery("select count(c) from RecordCorrection c where c.recordId=:id",Long.class).setParameter("id",record.getId()).getSingleResult();
        List<RecordCorrectionView> items=new ArrayList<>();
        for(RecordCorrection c:manager.createQuery("select c from RecordCorrection c where c.recordId=:id order by c.sequence",RecordCorrection.class).setParameter("id",record.getId()).setFirstResult((int)offset).setMaxResults(size).getResultList()) {verifyCorrection(c,record);items.add(view(c));}
        return new RecordCorrectionPage(items,total,page,size);
    }
    private int number(String text,int fallback,int max) {
        if(text==null)return fallback;
        if(!text.matches("[1-9][0-9]*"))throw invalid();
        try{int number=Integer.parseInt(text);if(number>max)throw invalid();return number;}catch(NumberFormatException ex){throw invalid();}
    }
    public RecordCorrectionView appendCorrection(CurrentActor actor,String recordId,AppendCorrectionRequest request,AuditContext context) {
        return appendCorrection(actor,recordId,request,Instant.now(),context);
    }
    RecordCorrectionView appendCorrection(CurrentActor actor,String recordId,AppendCorrectionRequest request,Instant now,AuditContext context) {
        ClinicalRecord record=access().doctorRecord(actor,recordId);
        Appointment a=lockDoctor(actor,record.getAppointmentId(),context);access().verifyRecord(record,a);
        if(!"COMPLETED".equals(a.getStatus()))throw conflict("STATE");
        if(request==null||request.getAfter()==null||now==null)throw invalid();
        long expected=ClinicalContentRules.requireRevision(request.getExpectedRevision());
        String key=ClinicalContentRules.requireOperationKey(request.getOperationKey());
        String reason=ClinicalContentRules.requireReason(request.getReason());
        ClinicalContentRequest input=request.getAfter();
        ClinicalContentView after=ClinicalContentRules.requireFinal(new ClinicalContentView(input.getComplaint(),input.getDiagnosis(),input.getAdvice(),input.getNotes()));
        List<RecordCorrection> existing=manager.createQuery("select c from RecordCorrection c where c.doctorId=:doctor and c.operationKey=:key",RecordCorrection.class).setParameter("doctor",a.getDoctorId()).setParameter("key",key).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if(!existing.isEmpty()) {
            RecordCorrection c=existing.get(0);
            manager.refresh(c,LockModeType.PESSIMISTIC_WRITE);
            if(!c.getRecordId().equals(recordId)||expected!=c.getSequence()-1||!same(after,c.after())||!reason.equals(c.getReason()))throw conflict("OPERATION");
            verifyCorrection(c,record);journal.append(context,c.getId(),AuditResult.NO_CHANGE,now);return view(c);
        }
        RecordCorrection latest=latest(record,true);long revision=latest==null?0:latest.getSequence();
        if(expected!=revision)throw conflict("REVISION");
        RecordCorrection c=new RecordCorrection(record,revision+1,latest==null?original(record):latest.after(),after,reason,key,now);
        manager.persist(c);journal.append(context,c.getId(),AuditResult.SUCCESS,now);return view(c);
    }
    private RecordCorrection latest(ClinicalRecord record,boolean locked) {
        TypedQuery<RecordCorrection> query=manager.createQuery("select c from RecordCorrection c where c.recordId=:id order by c.sequence desc",RecordCorrection.class).setParameter("id",record.getId()).setMaxResults(1);
        if(locked)query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        List<RecordCorrection> rows=query.getResultList();
        if(rows.isEmpty())return null;RecordCorrection c=rows.get(0);if(locked)manager.refresh(c,LockModeType.PESSIMISTIC_WRITE);verifyCorrection(c,record);return c;
    }
    private void verifyCorrection(RecordCorrection c,ClinicalRecord r) {
        if(!c.getRecordId().equals(r.getId())||!c.getAppointmentId().equals(r.getAppointmentId())||!c.getTenantId().equals(r.getTenantId())||!c.getDoctorId().equals(r.getDoctorId())||!c.getPatientId().equals(r.getPatientId()))throw new AccessDeniedException();
    }
    private Appointment lockDoctor(CurrentActor actor,String id,AuditContext context) {
        try{return access().lockedDoctorAppointment(actor,id,context);}catch(IllegalStateException ex){throw conflict("STATE");}
    }
    private Appointment lockPatient(CurrentActor actor,String id,AuditContext context) {
        try{return access().lockedPatientAppointment(actor,id,context);}catch(IllegalStateException ex){throw conflict("STATE");}
    }
    private static ClinicalContentView original(ClinicalRecord r){return new ClinicalContentView(r.getComplaint(),r.getDiagnosis(),r.getAdvice(),r.getNotes());}
    private static RecordCorrectionView view(RecordCorrection c){return new RecordCorrectionView(c.getId(),c.getSequence(),c.before(),c.after(),c.getReason(),c.getDoctorId(),format(c.getCreatedAt()));}
    private static boolean same(ClinicalContentView a,ClinicalContentView b){return Objects.equals(a.getComplaint(),b.getComplaint())&&Objects.equals(a.getDiagnosis(),b.getDiagnosis())&&Objects.equals(a.getAdvice(),b.getAdvice())&&Objects.equals(a.getNotes(),b.getNotes());}
    private static String format(Timestamp t){return t==null?null:DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t.toInstant().atOffset(ZoneOffset.ofHours(8)));}
    private static ClinicalSupplementException invalid(){return new ClinicalSupplementException(400,"CLINICAL_INPUT_INVALID");}
    private static ClinicalSupplementException conflict(String kind){return new ClinicalSupplementException(409,"CLINICAL_"+kind+"_CONFLICT");}
}
