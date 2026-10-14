package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.medcloud.course.contract.Role;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/** Projects only public attachment metadata; backend remains the authorization boundary. */
public final class AttachmentWorkspace {
    private final PaasClient paas;
    public AttachmentWorkspace(PaasClient paas){this.paas=paas;}
    public void load(HttpServletRequest request,String token,Role role) {
        if(role!=Role.PATIENT && role!=Role.DOCTOR)throw new IllegalArgumentException("无附件访问权限");
        String home=role==Role.PATIENT?"/patient":"/doctor";
        request.setAttribute("attachmentHome",home);request.setAttribute("attachmentWritable",false);request.setAttribute("attachmentDeleteAllowed",false);
        whitelist(request,"appointmentId","recordId","page","pageSize");
        String recordId=optional(request,"recordId"),appointmentId=optional(request,"appointmentId");JsonNode record=null;
        if(!recordId.isEmpty()) {
            if(role!=Role.DOCTOR)throw new IllegalArgumentException("无病历追加权限");recordId=id(recordId);
            record=find(paas.get("/doctor/records",token),recordId,"id");
            if(record==null)throw new PaasRejectedException(403,"无附件访问权限");
            String resolved=id(record.path("appointmentId").asText());
            if(!appointmentId.isEmpty()&&!id(appointmentId).equals(resolved))throw new IllegalArgumentException("预约与病历不一致");appointmentId=resolved;
        }
        appointmentId=id(appointmentId);int page=number(request,"page",1,Integer.MAX_VALUE),size=number(request,"pageSize",20,100);
        if(((long)page-1)*size>Integer.MAX_VALUE)throw new IllegalArgumentException("分页范围无效");
        // Parent authorization happens in this endpoint, even for an empty page.
        JsonNode data=paas.get(home+"/appointments/"+appointmentId+"/files?page="+page+"&pageSize="+size,token);
        request.setAttribute("attachmentPage",safePage(data,page,size));request.setAttribute("appointmentId",appointmentId);
        request.setAttribute("targetId",role==Role.PATIENT?appointmentId:recordId);
        JsonNode appointment=find(paas.get(home+"/appointments",token),appointmentId,"id");
        boolean writable=false,delete=false;
        if(appointment!=null) {
            String state=appointment.path("status").asText();
            if(role==Role.PATIENT) {
                boolean active="ACTIVE".equals(appointment.path("tenantStatus").asText()) && appointment.path("businessWritable").asBoolean(false);
                writable=active && ("BOOKED".equals(state)||"IN_PROGRESS".equals(state));
                // Current history DTO has no startedAt; PaaS rechecks it under the appointment lock.
                delete=active && "BOOKED".equals(state) && (!appointment.has("startedAt")||appointment.path("startedAt").isNull());
            } else {
                new WorkspaceAccess(paas).loadContext(request,token,role);
                if(record==null && "COMPLETED".equals(state))record=find(paas.get("/doctor/records",token),appointmentId,"appointmentId");
                if(record!=null){recordId=id(record.path("id").asText());request.setAttribute("targetId",recordId);}
                writable=record!=null && "COMPLETED".equals(state) && Boolean.TRUE.equals(request.getAttribute("businessWritable"));
            }
        }
        request.setAttribute("attachmentWritable",writable);request.setAttribute("attachmentDeleteAllowed",delete);
    }
    public static String id(String value) {
        if(value==null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("附件编号无效");
        return UUID.fromString(value).toString();
    }
    static String optional(HttpServletRequest r,String field){String[] values=r.getParameterValues(field);if(values==null)return "";if(values.length!=1||values[0]==null)throw new IllegalArgumentException("附件参数重复");return values[0];}
    static void whitelist(HttpServletRequest r,String... fields){Set<String> allowed=new HashSet<>(Arrays.asList(fields));for(String name:r.getParameterMap().keySet())if(!allowed.contains(name)||r.getParameterValues(name).length!=1)throw new IllegalArgumentException("附件参数不受支持或重复");}
    private static int number(HttpServletRequest r,String field,int fallback,int max){String v=optional(r,field);if(v.isEmpty())return fallback;try{if(!v.matches("[0-9]+"))throw new NumberFormatException();int n=Integer.parseInt(v);if(n<1||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new IllegalArgumentException("分页参数无效");}}
    private static JsonNode find(JsonNode rows,String value,String field){if(rows!=null&&rows.isArray())for(JsonNode row:rows)if(value.equals(row.path(field).asText()))return row;return null;}
    private static JsonNode safePage(JsonNode data,int page,int size) {
        if(!data.isObject()||!data.path("items").isArray()||data.path("page").asInt()!=page||data.path("pageSize").asInt()!=size
                ||!data.path("total").isIntegralNumber()||!data.path("total").canConvertToLong()||data.path("total").asLong()<0||data.path("items").size()>size)throw new IllegalArgumentException("附件列表暂不可用");
        ObjectNode safe=JsonNodeFactory.instance.objectNode();safe.put("page",page);safe.put("pageSize",size);safe.set("total",data.path("total"));ArrayNode items=safe.putArray("items");
        for(JsonNode row:data.path("items")) {
            id(row.path("fileId").asText());String source=row.path("sourceType").asText();
            if(!"PATIENT_APPOINTMENT".equals(source)&&!"DOCTOR_RECORD".equals(source))throw new IllegalArgumentException("附件列表暂不可用");
            ObjectNode item=items.addObject();for(String field:new String[]{"fileId","sourceType","fileName","contentType","sizeBytes","createdAt"})item.set(field,row.path(field));
        }
        return safe;
    }
}
