package com.medcloud.course.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DepartmentPage {
    private final List<DepartmentView> items;
    private final long total;
    private final int page;
    private final int size;
    public DepartmentPage(List<DepartmentView> items, long total, int page, int size) {
        this.items = Collections.unmodifiableList(new ArrayList<DepartmentView>(items));
        this.total = total; this.page = page; this.size = size;
    }
    public List<DepartmentView> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
