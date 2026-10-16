package com.medcloud.course.paas.consultation;

import com.medcloud.course.contract.ClinicalContentView;
import com.medcloud.course.paas.appointment.Appointment;
import javax.persistence.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="clinical_draft",uniqueConstraints=@UniqueConstraint(columnNames="appointment_id"))
public class ClinicalDraft {
    @Id @Column(length=36) private String id;
    @Column(name="appointment_id",nullable=false,length=36) private String appointmentId;
    @Column(name="tenant_id",nullable=false,length=36) private String tenantId;
    @Column(name="doctor_id",nullable=false,length=36) private String doctorId;
    @Column(name="patient_id",nullable=false,length=36) private String patientId;
    @Column(length=500) private String complaint;
    @Column(length=500) private String diagnosis;
    @Column(length=2000) private String advice;
    @Column(length=2000) private String notes;
    @Column(name="saved_at",nullable=false) private Timestamp savedAt;
    @Column(nullable=false) private long revision;
    protected ClinicalDraft() { }
    ClinicalDraft(Appointment appointment,ClinicalContentView content,Instant now) {
        id=UUID.randomUUID().toString();appointmentId=appointment.getId();tenantId=appointment.getTenantId();doctorId=appointment.getDoctorId();patientId=appointment.getPatientId();
        update(content,now);
    }
    void update(ClinicalContentView content,Instant now) {
        complaint=content.getComplaint();diagnosis=content.getDiagnosis();advice=content.getAdvice();notes=content.getNotes();savedAt=Timestamp.from(now);revision++;
    }
    public String getId(){return id;}
    public String getAppointmentId(){return appointmentId;}
    public String getTenantId(){return tenantId;}
    public String getDoctorId(){return doctorId;}
    public String getPatientId(){return patientId;}
    public long getRevision(){return revision;}
    public Timestamp getSavedAt(){return savedAt;}
    public ClinicalContentView content(){return new ClinicalContentView(complaint,diagnosis,advice,notes);}
}
