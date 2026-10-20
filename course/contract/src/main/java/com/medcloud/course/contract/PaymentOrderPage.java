package com.medcloud.course.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PaymentOrderPage {
    private final List<PaymentOrderView> items;
    private final long total;
    private final int page;
    private final int size;
    public PaymentOrderPage(List<PaymentOrderView> items, long total, int page, int size) {
        this.items = Collections.unmodifiableList(new ArrayList<PaymentOrderView>(items));
        this.total = total; this.page = page; this.size = size;
    }
    public List<PaymentOrderView> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
}
