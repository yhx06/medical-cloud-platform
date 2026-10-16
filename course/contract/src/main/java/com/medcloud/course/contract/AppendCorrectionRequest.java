package com.medcloud.course.contract;

public final class AppendCorrectionRequest {
    private Long expectedRevision;
    private String operationKey;
    private ClinicalContentRequest after;
    private String reason;

    public AppendCorrectionRequest() {
    }
    public Long getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(Long expectedRevision) { this.expectedRevision = expectedRevision; }
    public String getOperationKey() { return operationKey; }
    public void setOperationKey(String operationKey) { this.operationKey = operationKey; }
    public ClinicalContentRequest getAfter() { return after; }
    public void setAfter(ClinicalContentRequest after) { this.after = after; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
