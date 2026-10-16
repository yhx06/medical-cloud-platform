package com.medcloud.course.contract;

public final class SupplementComplaintRequest {
    private String description;
    private Long expectedRevision;

    public SupplementComplaintRequest() {
    }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(Long expectedRevision) { this.expectedRevision = expectedRevision; }
}
