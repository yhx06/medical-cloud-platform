package com.medcloud.course.contract;

public final class RecordCorrectionPage {
    private final java.util.List<RecordCorrectionView> items;
    private final long total;
    private final int page;
    private final int pageSize;

    public RecordCorrectionPage(java.util.List<RecordCorrectionView> items, long total, int page, int pageSize) {
        this.items = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(items));
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }
    public java.util.List<RecordCorrectionView> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }
}
