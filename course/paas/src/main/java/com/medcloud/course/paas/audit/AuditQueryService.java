package com.medcloud.course.paas.audit;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.statistics.StatisticsRange;
import javax.ejb.*;
import javax.persistence.*;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Scalar, read-only queries. COUNT and rows belong to one REQUIRED transaction. */
@Stateless
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class AuditQueryService {
    @PersistenceContext(unitName="medicalCloudCourse") private EntityManager manager;
    public AuditQueryService() {}
    public AuditQueryService(EntityManager manager){this.manager=manager;}
    public void requireReader(CurrentActor actor,boolean platform){readerTenant(actor,platform);}
    public AuditPage hospital(CurrentActor actor,AuditQuery query) {
        String tenant=readerTenant(actor,false);
        if(query==null || query.getTenantId()!=null)throw new IllegalArgumentException("hospital audit scope is fixed");
        return page(query,tenant);
    }
    public AuditPage platform(CurrentActor actor,AuditQuery query) {
        readerTenant(actor,true);
        if(query==null)throw new IllegalArgumentException("audit query required");
        if(query.getTenantId()!=null && manager.createQuery("select count(t) from Tenant t where t.id=:id",Long.class)
                .setParameter("id",query.getTenantId()).getSingleResult()!=1L)
            throw new IllegalArgumentException("audit tenant unavailable");
        return page(query,query.getTenantId());
    }
    private String readerTenant(CurrentActor actor,boolean platform) {
        if(actor==null || actor.getUserId()==null)throw new SecurityException("authentication required");
        Role required=platform?Role.PLATFORM_ADMIN:Role.HOSPITAL_ADMIN;
        if(actor.getRole()!=required)throw new AccessDeniedException();
        List<Object[]> accounts=manager.createQuery("select a.role,a.status,a.tenantId from Account a where a.id=:id",Object[].class)
                .setParameter("id",actor.getUserId()).getResultList();
        if(accounts.size()!=1 || !"ACTIVE".equals(accounts.get(0)[1]) || accounts.get(0)[0]==null)
            throw new SecurityException("inactive account");
        if(accounts.get(0)[0]!=required)throw new AccessDeniedException();
        String tenant=(String)accounts.get(0)[2];
        if(!platform) {
            List<String> status=manager.createQuery("select t.status from Tenant t where t.id=:id",String.class)
                    .setParameter("id",tenant).getResultList();
            if(tenant==null || status.size()!=1 || (!"ACTIVE".equals(status.get(0)) && !"DISABLED".equals(status.get(0))))
                throw new SecurityException("hospital unavailable");
        }
        return tenant;
    }
    private AuditPage page(AuditQuery query,String tenant) {
        StringBuilder where=new StringBuilder(" where e.recorded_at>=?1 and e.recorded_at<?2");
        List<Object> values=new ArrayList<Object>(); values.add(query.getLowerInclusive()); values.add(query.getUpperExclusive());
        if(tenant!=null)add(where,values,"e.tenant_id",tenant);
        if(query.getEventType()!=null)add(where,values,"e.event_type",query.getEventType().name());
        if(query.getResult()!=null)add(where,values,"e.result",query.getResult().name());
        long total=((Number)bind(manager.createNativeQuery("select count(*) from audit_event e"+where),values).getSingleResult()).longValue();
        List<?> rows=bind(manager.createNativeQuery("select e.id,e.request_id,e.tenant_id,t.name,e.actor_role,e.event_type,e.object_type,e.result,e.reason_code,e.recorded_at from audit_event e left join tenant t on t.id=e.tenant_id"+where+" order by e.recorded_at desc,e.id desc"),values)
                .setFirstResult(query.getOffset()).setMaxResults(query.getSize()).getResultList();
        List<AuditEventView> items=new ArrayList<AuditEventView>();
        for(Object item:rows) {
            Object[] r=(Object[])item;
            items.add(new AuditEventView((String)r[0],(String)r[1],(String)r[2],r[3]==null?"全局/归属未验证":(String)r[3],
                    r[4]==null?null:Role.valueOf((String)r[4]),AuditEventType.valueOf((String)r[5]),AuditObjectType.valueOf((String)r[6]),
                    AuditResult.valueOf((String)r[7]),AuditReasonCode.valueOf((String)r[8]),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(((Timestamp)r[9]).toInstant().atOffset(ZoneOffset.ofHours(8)))));
        }
        return new AuditPage(items,total,query.getPage(),query.getSize(),query.getFrom(),query.getTo(),StatisticsRange.ZONE.getId());
    }
    private static void add(StringBuilder where,List<Object> values,String column,Object value) {
        values.add(value); where.append(" and ").append(column).append("=?").append(values.size());
    }
    private static Query bind(Query query,List<Object> values) {
        for(int i=0;i<values.size();i++)query.setParameter(i+1,values.get(i)); return query;
    }
}
