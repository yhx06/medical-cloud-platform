package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.ClinicalContentView;
import javax.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="record_correction",uniqueConstraints={@UniqueConstraint(columnNames={"record_id","sequence_number"}),@UniqueConstraint(columnNames={"doctor_id","operation_key"})})
public class RecordCorrection {
    @Id @Column(length=36) private String id;
    @Column(name="record_id",nullable=false,length=36) private String recordId;
    @Column(name="appointment_id",nullable=false,length=36) private String appointmentId;
    @Column(name="tenant_id",nullable=false,length=36) private String tenantId;
    @Column(name="doctor_id",nullable=false,length=36) private String doctorId;
    @Column(name="patient_id",nullable=false,length=36) private String patientId;
    @Column(name="sequence_number",nullable=false) private long sequence;
    @Column(name="before_complaint",length=500) private String beforeComplaint;
    @Column(name="before_diagnosis",nullable=false,length=500) private String beforeDiagnosis;
    @Column(name="before_advice",nullable=false,length=2000) private String beforeAdvice;
    @Column(name="before_notes",length=2000) private String beforeNotes;
    @Column(name="after_complaint",length=500) private String afterComplaint;
    @Column(name="after_diagnosis",nullable=false,length=500) private String afterDiagnosis;
    @Column(name="after_advice",nullable=false,length=2000) private String afterAdvice;
    @Column(name="after_notes",length=2000) private String afterNotes;
    @Column(nullable=false,length=2000) private String reason;
    @Column(name="created_at",nullable=false) private Timestamp createdAt;
    @Column(name="operation_key",nullable=false,length=36) private String operationKey;
    protected RecordCorrection(){ }
    RecordCorrection(ClinicalRecord record,long sequence,ClinicalContentView before,ClinicalContentView after,String reason,String key,Instant now) {
        id=UUID.randomUUID().toString();recordId=record.getId();appointmentId=record.getAppointmentId();tenantId=record.getTenantId();doctorId=record.getDoctorId();patientId=record.getPatientId();this.sequence=sequence;
        beforeComplaint=before.getComplaint();beforeDiagnosis=before.getDiagnosis();beforeAdvice=before.getAdvice();beforeNotes=before.getNotes();
        afterComplaint=after.getComplaint();afterDiagnosis=after.getDiagnosis();afterAdvice=after.getAdvice();afterNotes=after.getNotes();this.reason=reason;operationKey=key;createdAt=Timestamp.from(now);
    }
    public String getId(){return id;}
    public String getRecordId(){return recordId;}
    public String getAppointmentId(){return appointmentId;}
    public String getTenantId(){return tenantId;}
    public String getDoctorId(){return doctorId;}
    public String getPatientId(){return patientId;}
    public long getSequence(){return sequence;}
    public String getReason(){return reason;}
    public String getOperationKey(){return operationKey;}
    public Timestamp getCreatedAt(){return createdAt;}
    public ClinicalContentView before(){return new ClinicalContentView(beforeComplaint,beforeDiagnosis,beforeAdvice,beforeNotes);}
    public ClinicalContentView after(){return new ClinicalContentView(afterComplaint,afterDiagnosis,afterAdvice,afterNotes);}
}
