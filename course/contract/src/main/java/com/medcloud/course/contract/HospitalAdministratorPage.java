package com.medcloud.course.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HospitalAdministratorPage {
    private final TenantView tenant;
    private final List<HospitalAdministratorView> items;
    private final long total;
    private final int page;
    private final int size;
    private final long activeAdministratorCount;

    public HospitalAdministratorPage(TenantView tenant, List<HospitalAdministratorView> items,
                                     long total, int page, int size, long activeAdministratorCount) {
        this.tenant = tenant;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.total = total;
        this.page = page;
        this.size = size;
        this.activeAdministratorCount = activeAdministratorCount;
    }
    public TenantView getTenant() { return tenant; }
    public List<HospitalAdministratorView> getItems() { return items; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getActiveAdministratorCount() { return activeAdministratorCount; }
}
