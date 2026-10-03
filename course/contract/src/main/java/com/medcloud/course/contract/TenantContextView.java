package com.medcloud.course.contract;

public final class TenantContextView {
    private final String id;
    private final String name;
    private final String status;
    private final boolean businessWritable;
    public TenantContextView(String id, String name, String status, boolean businessWritable) {
        this.id = id; this.name = name; this.status = status; this.businessWritable = businessWritable;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public boolean isBusinessWritable() { return businessWritable; }
}
