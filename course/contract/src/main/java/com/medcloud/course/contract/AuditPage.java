package com.medcloud.course.contract;

import java.util.*;

public final class AuditPage {
    private final List<AuditEventView> items;
    private final long total;
    private final int page, size;
    private final String from, to, timezone;
    public AuditPage(List<AuditEventView> items,long total,int page,int size,String from,String to,String timezone) {
        this.items=Collections.unmodifiableList(new ArrayList<AuditEventView>(items)); this.total=total;
        this.page=page; this.size=size; this.from=from; this.to=to; this.timezone=timezone;
    }
    public List<AuditEventView> getItems(){return items;}
    public long getTotal(){return total;}
    public int getPage(){return page;}
    public int getSize(){return size;}
    public String getFrom(){return from;}
    public String getTo(){return to;}
    public String getTimezone(){return timezone;}
}
