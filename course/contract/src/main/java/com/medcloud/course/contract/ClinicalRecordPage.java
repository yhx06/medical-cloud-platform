package com.medcloud.course.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClinicalRecordPage {
    private final List<ClinicalRecordView> items;
    private final long total;
    private final int page;
    private final int size;
    public ClinicalRecordPage(List<ClinicalRecordView> items, long total, int page, int size) {
        this.items = Collections.unmodifiableList(new ArrayList<ClinicalRecordView>(items));
        this.total = total; this.page = page; this.size = size;
    }
    public List<ClinicalRecordView> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
