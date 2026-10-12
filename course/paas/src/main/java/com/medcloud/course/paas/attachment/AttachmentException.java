package com.medcloud.course.paas.attachment;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class AttachmentException extends RuntimeException {
    private static final Map<String,Integer> CODES;
    static {
        Map<String,Integer> codes = new HashMap<>();
        codes.put("FILE_INVALID",400); codes.put("UPLOAD_DESCRIPTOR_INVALID",400);
        codes.put("FILE_TOO_LARGE",413); codes.put("FILE_TYPE_UNSUPPORTED",415);
        codes.put("UPLOAD_IN_PROGRESS",409); codes.put("UPLOAD_CONFLICT",409); codes.put("FILE_STATE_CONFLICT",409);
        codes.put("FILE_LIMIT_REACHED",429); codes.put("OBJECT_STORE_UNAVAILABLE",503);
        codes.put("UNAUTHORIZED",401); codes.put("FORBIDDEN",403); codes.put("INTERNAL_ERROR",500);
        CODES=Collections.unmodifiableMap(codes);
    }
    private final int status;
    private final String code;
    public AttachmentException(int status, String code) {
        super("Attachment operation failed");
        if (!Integer.valueOf(status).equals(CODES.get(code))) throw new IllegalArgumentException("Invalid attachment error");
        this.status=status; this.code=code;
    }
    public int getStatus() { return status; }
    public String getCode() { return code; }
}
