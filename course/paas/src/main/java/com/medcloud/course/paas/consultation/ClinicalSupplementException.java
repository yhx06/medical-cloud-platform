package com.medcloud.course.paas.consultation;

public final class ClinicalSupplementException extends RuntimeException {
    private final int status;
    private final String code;

    public ClinicalSupplementException(int status, String code) {
        super("Clinical operation failed");
        boolean valid = status == 400 && "CLINICAL_INPUT_INVALID".equals(code)
                || status == 409 && ("CLINICAL_STATE_CONFLICT".equals(code)
                || "CLINICAL_REVISION_CONFLICT".equals(code)
                || "CLINICAL_OPERATION_CONFLICT".equals(code));
        if (!valid) throw new IllegalArgumentException("Invalid clinical error");
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
}
