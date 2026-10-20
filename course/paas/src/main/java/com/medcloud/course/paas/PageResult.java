package com.medcloud.course.paas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Service-layer pagination carrier that keeps entities so callers can map them to their own views. */
public final class PageResult<T> {
    private final List<T> items;
    private final long total;
    private final int page;
    private final int size;
    public PageResult(List<T> items, long total, int page, int size) {
        this.items = Collections.unmodifiableList(new ArrayList<T>(items));
        this.total = total; this.page = page; this.size = size;
    }
    public List<T> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
