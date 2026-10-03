package com.medcloud.course.contract;

public final class TenantCounts {
    private final long total;
    private final long active;
    private final long disabled;

    public TenantCounts(long total, long active, long disabled) {
        this.total = total;
        this.active = active;
        this.disabled = disabled;
    }

    public long getTotal() { return total; }
    public long getActive() { return active; }
    public long getDisabled() { return disabled; }
}
