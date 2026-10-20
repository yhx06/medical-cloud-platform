package com.medcloud.course.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SlotPage {
    private final List<SlotView> items;
    private final long total;
    private final int page;
    private final int size;
    public SlotPage(List<SlotView> items, long total, int page, int size) {
        this.items = Collections.unmodifiableList(new ArrayList<SlotView>(items));
        this.total = total; this.page = page; this.size = size;
    }
    public List<SlotView> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
