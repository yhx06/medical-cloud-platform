package com.medcloud.course.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DoctorPage {
    private final List<DoctorView> items;
    private final long total;
    private final int page;
    private final int size;
    public DoctorPage(List<DoctorView> items, long total, int page, int size) {
        this.items = Collections.unmodifiableList(new ArrayList<DoctorView>(items));
        this.total = total; this.page = page; this.size = size;
    }
    public List<DoctorView> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
