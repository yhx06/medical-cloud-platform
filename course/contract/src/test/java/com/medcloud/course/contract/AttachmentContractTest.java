package com.medcloud.course.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private AttachmentView file() { return new AttachmentView("file-1", AttachmentSourceType.PATIENT_APPOINTMENT, "检查.pdf", "application/pdf", 12L, "2026-10-12T12:00:00+08:00"); }
    @Test void metadataSerializationContainsOnlyApprovedFields() throws Exception {
        Set<String> fields = new HashSet<>(); json.valueToTree(file()).fieldNames().forEachRemaining(fields::add);
        assertEquals(new HashSet<>(Arrays.asList("fileId", "sourceType", "fileName", "contentType", "sizeBytes", "createdAt")), fields);
    }
    @Test void operationIdEqualsPublishedFileId() {
        UploadOperationView operation = new UploadOperationView("file-1", AttachmentState.AVAILABLE, file());
        assertEquals(operation.getUploadId(), operation.getFile().getFileId());
    }
    @Test void fileEventsUseFileMetadataObjectType() {
        for (String name : Arrays.asList("FILE_UPLOAD", "FILE_DELETE", "FILE_DOWNLOAD_AUTHORIZE")) assertEquals(AuditObjectType.FILE_METADATA, AuditEventType.valueOf(name).getObjectType());
        assertEquals(AuditObjectType.PAYMENT_ORDER, AuditEventType.PAYMENT_PAY.getObjectType());
    }
    @Test void internalFieldsNeverAppearInPublicViews() throws Exception {
        String output = json.writeValueAsString(new UploadOperationView("file-1", AttachmentState.AVAILABLE, file()));
        for (String field : Arrays.asList("objectKey", "attemptToken", "sha256", "bucket", "leaseUntil")) assertFalse(output.contains(field));
    }
    @Test void listIsDefensivelyCopiedAndPrepareIsDeserializable() throws Exception {
        List<AttachmentView> items = new ArrayList<>(); items.add(file()); AttachmentPage page = new AttachmentPage(items, 1, 1, 20); items.clear();
        assertEquals(1, page.getItems().size()); assertThrows(UnsupportedOperationException.class, () -> page.getItems().clear());
        PrepareUploadRequest request = json.readValue("{\"uploadKey\":\"key\",\"fileName\":\"a.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":12,\"sha256\":\"hash\"}", PrepareUploadRequest.class);
        assertEquals(12, request.getSizeBytes()); assertEquals("key", request.getUploadKey());
        assertEquals(2, AttachmentSourceType.values().length); assertEquals(6, AttachmentState.values().length);
    }
}
