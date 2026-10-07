package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medcloud.course.contract.*;
import javax.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Authenticated audit reads and directory degrade independently; invalid filters never broaden a read. */
public final class AuditWorkspace {
    private static final String[] INPUTS = {"auditFrom", "auditTo", "auditEventType", "auditResult", "auditPage", "auditSize", "auditTenantId"};
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(INPUTS));
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final PaasClient paas;
    public AuditWorkspace(PaasClient paas) { this.paas = paas; }

    public void load(HttpServletRequest request, String token, Role role) {
        for (String name : new String[]{"auditPage", "auditError", "auditHospitals", "auditDirectoryError", "auditTenantName"}) request.removeAttribute(name);
        // Keep invalid values visible, escaped exactly once, while refusing the API read.
        for (String name : INPUTS) request.setAttribute("auditPage".equals(name) ? "auditPageNumber" : name, WebEscaper.html(raw(request, name)));
        try {
            String query = query(request, role);
            JsonNode page = safePage(paas.get((role == Role.PLATFORM_ADMIN ? "/platform" : "/hospital") + "/audit-events" + query, token));
            request.setAttribute("auditPage", page);
            for (String field : new String[]{"from", "to"}) request.setAttribute("audit" + (field.equals("from") ? "From" : "To"), WebEscaper.html(page.path(field).asText()));
            request.setAttribute("auditPageNumber", page.path("page").asInt()); request.setAttribute("auditSize", page.path("size").asInt());
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException failure) {
            WorkspaceAccess.rethrowUnauthorized(failure); request.setAttribute("auditError", WebEscaper.html(failure.getMessage()));
        }
        if (role == Role.PLATFORM_ADMIN) directory(request, token);
    }

    public static String query(HttpServletRequest request, Role role) {
        if (role != Role.HOSPITAL_ADMIN && role != Role.PLATFORM_ADMIN) throw new IllegalArgumentException("当前账号没有审计查询权限");
        for (String name : request.getParameterMap().keySet()) if (name.startsWith("audit")) {
            String[] values = request.getParameterValues(name);
            if (!ALLOWED.contains(name) || values == null || values.length != 1 || values[0] == null)
                throw new IllegalArgumentException("审计筛选参数重复或不受支持");
        }
        if (role == Role.HOSPITAL_ADMIN && request.getParameterMap().containsKey("auditTenantId"))
            throw new IllegalArgumentException("医院账号只能查询本院审计，不接受医院覆盖参数");
        String from = raw(request,"auditFrom"), to = raw(request,"auditTo"); StringBuilder q = new StringBuilder();
        if (!from.isEmpty() || !to.isEmpty()) {
            if (from.isEmpty() || to.isEmpty()) throw new IllegalArgumentException("请同时填写审计起止日期");
            LocalDate start = StatisticsWorkspace.date(from), end = StatisticsWorkspace.date(to);
            if (start.getYear()<1000 || end.equals(LocalDate.of(9999,12,31)) || start.isAfter(end) || ChronoUnit.DAYS.between(start,end)>=366)
                throw new IllegalArgumentException("审计日期范围须为有效的 1–366 个自然日");
            append(q,"from",from); append(q,"to",to);
        }
        String event=raw(request,"auditEventType"), result=raw(request,"auditResult");
        if (!event.isEmpty()) { enumValue(AuditEventType.class,event); append(q,"eventType",event); }
        if (!result.isEmpty()) { enumValue(AuditResult.class,result); append(q,"result",result); }
        int page=number(request,"auditPage",1,Integer.MAX_VALUE), size=number(request,"auditSize",20,100);
        if (((long)page-1)*size>Integer.MAX_VALUE) throw new IllegalArgumentException("审计分页范围无效");
        append(q,"page",String.valueOf(page)); append(q,"size",String.valueOf(size));
        String tenant=raw(request,"auditTenantId");
        if (!tenant.isEmpty()) {
            if (!tenant.matches(UUID)) throw new IllegalArgumentException("审计医院编号无效");
            append(q,"tenantId",java.util.UUID.fromString(tenant).toString());
        }
        return "?"+q;
    }
    private static String raw(HttpServletRequest r,String name) {String v=r.getParameter(name);return v==null?"":v.trim();}
    private static int number(HttpServletRequest r,String name,int fallback,int max) {
        if (!r.getParameterMap().containsKey(name)) return fallback;
        try {String v=raw(r,name);if(!v.matches("[0-9]+"))throw new NumberFormatException();int n=Integer.parseInt(v);if(n<1||n>max)throw new NumberFormatException();return n;}
        catch(NumberFormatException bad){throw new IllegalArgumentException("审计分页参数无效");}
    }
    private static <E extends Enum<E>> void enumValue(Class<E> type,String value) {
        try {Enum.valueOf(type,value);}catch(IllegalArgumentException bad){throw new IllegalArgumentException("审计事件类型或结果无效");}
    }
    private static void append(StringBuilder q,String key,String value) {
        if(q.length()>0)q.append('&');try{q.append(key).append('=').append(URLEncoder.encode(value,"UTF-8"));}
        catch(UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}
    }
    private static IllegalArgumentException shape() {return new IllegalArgumentException("审计服务返回的信息不完整，请稍后重试");}
    private static boolean text(JsonNode n,String field,int max) {return n.path(field).isTextual()&&!n.path(field).asText().isEmpty()&&n.path(field).asText().length()<=max;}
    private static boolean nullableText(JsonNode n,String field,int max) {return n.has(field)&&(n.path(field).isNull()||text(n,field,max));}
    private static JsonNode safePage(JsonNode n) {
        if(!n.isObject()||!n.path("items").isArray()||!n.path("total").isIntegralNumber()||!n.path("total").canConvertToLong()||n.path("total").asLong()<0
                ||!n.path("page").isIntegralNumber()||!n.path("page").canConvertToInt()||n.path("page").asInt()<1
                ||!n.path("size").isIntegralNumber()||!n.path("size").canConvertToInt()||n.path("size").asInt()<1||n.path("size").asInt()>100
                ||((long)n.path("page").asInt()-1)*n.path("size").asInt()>Integer.MAX_VALUE
                ||!text(n,"from",10)||!text(n,"to",10)||!"Asia/Shanghai".equals(n.path("timezone").asText())
                ||n.path("items").size()>n.path("size").asInt()||n.path("items").size()>n.path("total").asLong())throw shape();
        try {LocalDate from=StatisticsWorkspace.date(n.path("from").asText()),to=StatisticsWorkspace.date(n.path("to").asText());
            if(from.getYear()<1000||to.equals(LocalDate.of(9999,12,31))||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>=366)throw shape();
        }catch(IllegalArgumentException bad){throw shape();}
        ObjectNode safe=JsonNodeFactory.instance.objectNode();for(String field:new String[]{"total","page","size","from","to","timezone"})safe.set(field,n.path(field));
        ArrayNode rows=safe.putArray("items");
        for(JsonNode row:n.path("items")) {
            if(!row.isObject()||!text(row,"id",36)||!text(row,"requestId",128)||!nullableText(row,"tenantId",36)||!nullableText(row,"tenantName",100)
                    ||!nullableText(row,"actorRole",32)||!text(row,"actorLabel",100)||!text(row,"recordedAt",64))throw shape();
            try {
                Enum.valueOf(AuditEventType.class,row.path("eventType").asText());Enum.valueOf(AuditObjectType.class,row.path("objectType").asText());
                Enum.valueOf(AuditResult.class,row.path("result").asText());Enum.valueOf(AuditReasonCode.class,row.path("reasonCode").asText());
                if(!row.path("actorRole").isNull())Enum.valueOf(Role.class,row.path("actorRole").asText());
                OffsetDateTime.parse(row.path("recordedAt").asText());
            }catch(RuntimeException bad){throw shape();}
            ObjectNode projected=rows.addObject();for(String field:new String[]{"id","requestId","tenantId","tenantName","actorRole","actorLabel","eventType","objectType","result","reasonCode","recordedAt"})projected.set(field,row.path(field));
        }
        return safe;
    }
    private void directory(HttpServletRequest r,String token) {
        try {
            ArrayNode hospitals=JsonNodeFactory.instance.arrayNode();long total=-1;Set<String> ids=new HashSet<String>();
            for(int page=1;;page++) {
                JsonNode data=paas.get("/platform/tenants?page="+page+"&size=100",token);
                if(!data.isObject()||!data.path("items").isArray()||!data.path("total").isIntegralNumber()||!data.path("total").canConvertToLong()
                        ||data.path("total").asLong()<0||data.path("total").asLong()>Integer.MAX_VALUE||data.path("page").asInt()!=page||data.path("size").asInt()!=100)throw new IllegalArgumentException("审计医院目录信息不完整，请刷新重试");
                if(total<0)total=data.path("total").asLong();
                if(total!=data.path("total").asLong()||data.path("items").size()!=Math.min(100,total-hospitals.size()))throw new IllegalArgumentException("审计医院目录已变化，请刷新重试");
                for(JsonNode row:data.path("items")) {
                    if(!text(row,"id",36)||!row.path("id").asText().matches(UUID)||!text(row,"name",100)||!ids.add(row.path("id").asText()))throw new IllegalArgumentException("审计医院目录信息不完整，请刷新重试");
                    ObjectNode safe=hospitals.addObject();safe.set("id",row.path("id"));safe.set("name",row.path("name"));
                    if(row.path("id").asText().equalsIgnoreCase(raw(r,"auditTenantId")))r.setAttribute("auditTenantName",WebEscaper.html(row.path("name").asText()));
                }
                if(hospitals.size()==total)break;
            }
            r.setAttribute("auditHospitals",hospitals);
        }catch(PaasRejectedException|PaasUnavailableException|IllegalArgumentException failure) {
            WorkspaceAccess.rethrowUnauthorized(failure);r.removeAttribute("auditHospitals");r.removeAttribute("auditTenantName");
            r.setAttribute("auditDirectoryError",WebEscaper.html("审计医院目录暂不可用，请刷新后重试"));
        }
    }
}
