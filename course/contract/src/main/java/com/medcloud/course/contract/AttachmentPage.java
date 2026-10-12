package com.medcloud.course.contract;

public final class AttachmentPage {
    private final java.util.List<AttachmentView> items;
    private final long total;
    private final int page;
    private final int pageSize;
    public AttachmentPage(java.util.List<AttachmentView> items, long total, int page, int pageSize) {
        this.items = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(items));
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }
    public java.util.List<AttachmentView> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }
}
