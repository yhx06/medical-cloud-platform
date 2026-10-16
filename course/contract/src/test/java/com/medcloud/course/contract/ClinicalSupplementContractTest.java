package com.medcloud.course.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClinicalSupplementContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ClinicalContentView content = new ClinicalContentView("c", "d", "a", "n");

    private void fields(Object value, String... expected) {
        Set<String> actual = new HashSet<>();
        json.valueToTree(value).fieldNames().forEachRemaining(actual::add);
        assertEquals(new HashSet<>(Arrays.asList(expected)), actual);
    }

    @Test void jsonOnlyApprovedFields() throws Exception {
        fields(content, "complaint", "diagnosis", "advice", "notes");
        fields(new PatientComplaintView("apt", "c", "2026-10-16T10:00:00+08:00", 1, true),
                "appointmentId", "description", "updatedAt", "revision", "writable");
        ClinicalDraftView draft = new ClinicalDraftView(content, 1, "2026-10-16T10:00:00+08:00");
        fields(draft, "content", "revision", "savedAt");
        fields(new ClinicalWorkspaceView("apt", "IN_PROGRESS", null, draft, null, 1),
                "appointmentId", "status", "patientComplaint", "draft", "recordId", "currentRevision");
        fields(new ClinicalRecordDetailsView("rec", "apt", content, content, 0, "time"),
                "recordId", "appointmentId", "original", "effective", "currentRevision", "completedAt");
        fields(correction(), "id", "sequence", "before", "after", "reason", "doctorId", "createdAt");
        fields(new RecordCorrectionPage(Collections.singletonList(correction()), 1, 1, 20),
                "items", "total", "page", "pageSize");
        ClinicalWorkspaceView empty = new ClinicalWorkspaceView("apt", "IN_PROGRESS", null, null, null, 0);
        assertTrue(json.valueToTree(empty).get("draft").isNull());
        assertEquals(0, empty.getCurrentRevision());
    }

    @Test void mutableRequestsDeserializeApprovedFields() throws Exception {
        ClinicalContentRequest input = json.readValue("{\"complaint\":\"c\",\"diagnosis\":\"d\",\"advice\":\"a\",\"notes\":\"n\"}", ClinicalContentRequest.class);
        fields(input, "complaint", "diagnosis", "advice", "notes");
        assertEquals("c", input.getComplaint()); assertEquals("d", input.getDiagnosis());
        assertEquals("a", input.getAdvice()); assertEquals("n", input.getNotes());
        SupplementComplaintRequest complaint = json.readValue("{\"description\":\"c\",\"expectedRevision\":0}", SupplementComplaintRequest.class);
        fields(complaint, "description", "expectedRevision");
        assertEquals("c", complaint.getDescription()); assertEquals(Long.valueOf(0), complaint.getExpectedRevision());
        SaveClinicalDraftRequest draft = json.readValue("{\"complaint\":\"c\",\"diagnosis\":\"d\",\"advice\":\"a\",\"notes\":\"n\",\"expectedRevision\":1}", SaveClinicalDraftRequest.class);
        fields(draft, "complaint", "diagnosis", "advice", "notes", "expectedRevision");
        assertEquals("c", draft.getComplaint()); assertEquals("d", draft.getDiagnosis());
        assertEquals("a", draft.getAdvice()); assertEquals("n", draft.getNotes());
        assertEquals(Long.valueOf(1), draft.getExpectedRevision());
        AppendCorrectionRequest correction = json.readValue("{\"expectedRevision\":2,\"operationKey\":\"key\",\"after\":{\"diagnosis\":\"d\",\"advice\":\"a\"},\"reason\":\"reason\"}", AppendCorrectionRequest.class);
        fields(correction, "expectedRevision", "operationKey", "after", "reason");
        assertEquals(Long.valueOf(2), correction.getExpectedRevision());
        assertEquals("key", correction.getOperationKey()); assertEquals("reason", correction.getReason());
        assertEquals("d", correction.getAfter().getDiagnosis());
    }

    @Test void legacyCompleteOmitsDraftRevision() throws Exception {
        CompleteConsultationRequest legacy = json.readValue("{\"diagnosis\":\"d\",\"advice\":\"a\"}", CompleteConsultationRequest.class);
        assertNull(legacy.getExpectedDraftRevision());
        assertNull(new CompleteConsultationRequest("d", "a").getExpectedDraftRevision());
        CompleteConsultationRequest current = json.readValue("{\"diagnosis\":\"d\",\"advice\":\"a\",\"expectedDraftRevision\":3}", CompleteConsultationRequest.class);
        assertEquals(Long.valueOf(3), current.getExpectedDraftRevision());
    }

    @Test void immutableCorrectionPage() {
        List<RecordCorrectionView> items = new ArrayList<>(); items.add(correction());
        RecordCorrectionPage page = new RecordCorrectionPage(items, 1, 1, 20); items.clear();
        assertEquals(1, page.getItems().size());
        assertThrows(UnsupportedOperationException.class, () -> page.getItems().clear());
        assertEquals(1, page.getTotal()); assertEquals(1, page.getPage()); assertEquals(20, page.getPageSize());
    }

    @Test void clinicalAuditEventsOnlyIdentifyObjects() {
        assertEquals(AuditObjectType.APPOINTMENT, AuditEventType.APPOINTMENT_COMPLAINT_UPDATE.getObjectType());
        assertEquals(AuditObjectType.CLINICAL_DRAFT, AuditEventType.CONSULTATION_DRAFT_SAVE.getObjectType());
        assertEquals(AuditObjectType.RECORD_CORRECTION, AuditEventType.RECORD_CORRECTION_APPEND.getObjectType());
    }

    private RecordCorrectionView correction() {
        return new RecordCorrectionView("cor", 1, content, content, "reason", "doctor", "time");
    }
}
