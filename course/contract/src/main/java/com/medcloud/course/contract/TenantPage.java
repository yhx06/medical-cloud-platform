package com.medcloud.course.contract;

import java.util.List;

public final class TenantPage {
    private final List<TenantView> items;
    private final long total;
    private final int page;
    private final int size;
    public TenantPage(List<TenantView> items, long total, int page, int size) {
        this.items = items; this.total = total; this.page = page; this.size = size;
    }
    public List<TenantView> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
