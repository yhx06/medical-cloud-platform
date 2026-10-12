package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.PrepareUploadRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentDescriptorsTest {
    private PrepareUploadRequest request(String name, String mime, long size, String hash) { return new PrepareUploadRequest("12345678-1234-1234-1234-123456789abc", name, mime, size, hash); }
    private final String hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789";
    @Test void normalizesBasenameAndDigestWithoutTrustingContents() {
        PrepareUploadRequest original = request("C:\\fakepath\\检查.PDF", "application/pdf", 10485760L, hash);
        PrepareUploadRequest valid = AttachmentDescriptors.requireValid(original, new AttachmentLimits());
        assertEquals("检查.PDF", valid.getFileName()); assertEquals(hash.toLowerCase(java.util.Locale.ROOT), valid.getSha256());
        assertEquals("C:\\fakepath\\检查.PDF", original.getFileName());
    }
    @Test void rejectsInvalidDescriptorsAndUsesSafeErrors() {
        for (PrepareUploadRequest bad : new PrepareUploadRequest[]{request("a.pdf", "image/png", 1, hash), request("a.pdf", "application/pdf", 0, hash), request("a.pdf", "application/pdf", 1, "x"), request("\r\na.pdf", "application/pdf", 1, hash), request("../", "application/pdf", 1, hash)}) {
            AttachmentException error = assertThrows(AttachmentException.class, () -> AttachmentDescriptors.requireValid(bad, new AttachmentLimits()));
            assertEquals(400, error.getStatus()); assertEquals("UPLOAD_DESCRIPTOR_INVALID", error.getCode());
        }
        AttachmentException large = assertThrows(AttachmentException.class, () -> AttachmentDescriptors.requireValid(request("a.pdf", "application/pdf", 10485761L, hash), new AttachmentLimits()));
        assertEquals(413, large.getStatus()); assertEquals("FILE_TOO_LARGE", large.getCode());
        assertThrows(IllegalArgumentException.class, () -> new AttachmentException(500, "secret"));
    }
}
