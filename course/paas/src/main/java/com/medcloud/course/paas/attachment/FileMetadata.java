package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.*;
import javax.persistence.*;
import java.sql.Timestamp;

/** Scalar ownership and durable upload/cleanup intent; never exposes an entity to HTTP. */
@Entity
@Table(name="file_metadata", uniqueConstraints={
    @UniqueConstraint(name="uk_attachment_upload",columnNames={"uploader_id","upload_key"}),
    @UniqueConstraint(name="uk_attachment_object",columnNames="object_key")}, indexes={
    @Index(name="idx_attachment_listing",columnList="appointment_id,source_type,state,created_at,id"),
    @Index(name="idx_attachment_tenant",columnList="tenant_id,state"),
    @Index(name="idx_attachment_cleanup",columnList="state,next_retry_at,id"),
    @Index(name="idx_attachment_lease",columnList="state,lease_until,id")})
public class FileMetadata {
    @Id @Column(length=36) String id;
    @Column(name="tenant_id",nullable=false,length=36) String tenantId;
    @Column(name="appointment_id",nullable=false,length=36) String appointmentId;
    @Column(name="patient_id",nullable=false,length=36) String patientId;
    @Column(name="doctor_id",nullable=false,length=36) String doctorId;
    @Column(name="uploader_id",nullable=false,length=36) String uploaderId;
    @Column(name="record_id",length=36) String recordId;
    @Enumerated(EnumType.STRING) @Column(name="source_type",nullable=false,length=32) AttachmentSourceType sourceType;
    @Column(name="file_name",nullable=false,length=255) String fileName;
    @Column(name="content_type",nullable=false,length=64) String contentType;
    @Column(name="size_bytes",nullable=false) long sizeBytes;
    @Column(nullable=false,length=64) String sha256;
    @Column(name="object_key",nullable=false,length=128) String objectKey;
    @Column(name="upload_key",nullable=false,length=36) String uploadKey;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) AttachmentState state;
    @Column(name="created_at",nullable=false) Timestamp createdAt;
    @Column(name="updated_at",nullable=false) Timestamp updatedAt;
    @Column(name="lease_until") Timestamp leaseUntil;
    @Column(name="deleted_at") Timestamp deletedAt;
    @Column(name="attempt_token",length=36) String attemptToken;
    @Column(name="retry_count",nullable=false) int retryCount;
    @Column(name="next_retry_at") Timestamp nextRetryAt;
    @Version @Column(nullable=false) long version;
    protected FileMetadata() { }
    AttachmentView view() { return new AttachmentView(id,sourceType,fileName,contentType,sizeBytes,createdAt.toInstant().toString()); }
    UploadOperationView operation() { return new UploadOperationView(id,state,state==AttachmentState.AVAILABLE?view():null); }
}
