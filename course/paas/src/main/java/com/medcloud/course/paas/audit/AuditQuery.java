package com.medcloud.course.paas.audit;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.statistics.StatisticsRange;
import javax.ws.rs.core.MultivaluedMap;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/** Parse raw parameters only after authentication and current reader authorization. */
public final class AuditQuery {
    private static final Set<String> COMMON=new HashSet<String>(Arrays.asList("from","to","eventType","result","page","size"));
    private final StatisticsRange range;
    private final AuditEventType eventType;
    private final AuditResult result;
    private final String tenantId;
    private final int page, size, offset;
    private AuditQuery(StatisticsRange range,AuditEventType eventType,AuditResult result,String tenantId,int page,int size,int offset) {
        this.range=range; this.eventType=eventType; this.result=result; this.tenantId=tenantId; this.page=page; this.size=size; this.offset=offset;
    }
    public static AuditQuery parse(MultivaluedMap<String,String> parameters,boolean platform) {
        if(parameters==null) throw new IllegalArgumentException("query required");
        for(Map.Entry<String,List<String>> entry:parameters.entrySet()) {
            if((!COMMON.contains(entry.getKey()) && !(platform && "tenantId".equals(entry.getKey())))
                    || entry.getValue()==null || entry.getValue().size()!=1 || entry.getValue().get(0)==null)
                throw new IllegalArgumentException("unsupported or duplicate audit parameter");
        }
        StatisticsRange range=StatisticsRange.parse(parameters.getFirst("from"),parameters.getFirst("to"),Clock.system(StatisticsRange.ZONE));
        AuditEventType event=enumeration(AuditEventType.class,parameters.getFirst("eventType"));
        AuditResult result=enumeration(AuditResult.class,parameters.getFirst("result"));
        String tenant=parameters.getFirst("tenantId");
        if(tenant!=null) {
            if(!tenant.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException("invalid audit tenant");
            tenant=UUID.fromString(tenant).toString();
        }
        int page=number(parameters.getFirst("page"),1),size=number(parameters.getFirst("size"),20);
        long offset=((long)page-1)*size;
        if(page<1 || size<1 || size>100 || offset>Integer.MAX_VALUE) throw new IllegalArgumentException("invalid audit pagination");
        return new AuditQuery(range,event,result,tenant,page,size,(int)offset);
    }
    private static int number(String value,int defaultValue) {
        if(value==null)return defaultValue;
        if(!value.matches("[0-9]+"))throw new IllegalArgumentException("invalid audit pagination");
        return Integer.parseInt(value);
    }
    private static <E extends Enum<E>> E enumeration(Class<E> type,String value) {
        return value==null || value.trim().isEmpty()?null:Enum.valueOf(type,value.trim());
    }
    public String getFrom(){return range.getFrom().toString();}
    public String getTo(){return range.getTo().toString();}
    public AuditEventType getEventType(){return eventType;}
    public AuditResult getResult(){return result;}
    public String getTenantId(){return tenantId;}
    public int getPage(){return page;}
    public int getSize(){return size;}
    public int getOffset(){return offset;}
    public Timestamp getLowerInclusive(){return Timestamp.from(range.getStart());}
    public Timestamp getUpperExclusive(){return Timestamp.from(range.getEndExclusive());}
}
