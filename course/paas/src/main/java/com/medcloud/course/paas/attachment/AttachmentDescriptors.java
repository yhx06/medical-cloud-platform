package com.medcloud.course.paas.attachment;

import com.medcloud.course.contract.PrepareUploadRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AttachmentDescriptors {
    private AttachmentDescriptors() { }
    public static PrepareUploadRequest requireValid(PrepareUploadRequest request, AttachmentLimits limits) {
        if (request == null || limits == null || request.getUploadKey() == null || !request.getUploadKey().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || request.getSizeBytes() < 1 || request.getSha256() == null || !request.getSha256().matches("(?i)[0-9a-f]{64}")) throw invalid();
        if (request.getSizeBytes() > limits.getMaxBytes()) throw new AttachmentException(413,"FILE_TOO_LARGE");
        String name=request.getFileName();
        if (name == null) throw invalid();
        for (int i=0;i<name.length();i++) if (Character.isISOControl(name.charAt(i))) throw invalid();
        name=name.substring(Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'))+1);
        if (name.trim().isEmpty() || name.getBytes(StandardCharsets.UTF_8).length > 255) throw invalid();
        String lower=name.toLowerCase(Locale.ROOT), mime=request.getContentType();
        boolean valid=(lower.endsWith(".jpg") || lower.endsWith(".jpeg")) && "image/jpeg".equals(mime) || lower.endsWith(".png") && "image/png".equals(mime) || lower.endsWith(".pdf") && "application/pdf".equals(mime);
        if (!valid) throw invalid();
        return new PrepareUploadRequest(request.getUploadKey().toLowerCase(Locale.ROOT), name, mime, request.getSizeBytes(), request.getSha256().toLowerCase(Locale.ROOT));
    }
    private static AttachmentException invalid() { return new AttachmentException(400,"UPLOAD_DESCRIPTOR_INVALID"); }
}
