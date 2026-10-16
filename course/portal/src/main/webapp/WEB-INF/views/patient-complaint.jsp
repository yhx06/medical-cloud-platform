<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode,com.medcloud.course.portal.web.WebEscaper" %>
<%! private String value(javax.servlet.http.HttpServletRequest r,String key){Object v=r.getAttribute(key);return WebEscaper.html(v==null?"":String.valueOf(v));} %>
<% String context=request.getContextPath();JsonNode complaint=(JsonNode)request.getAttribute("patientComplaint"); %>
<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>患者病情补充</title><link rel="stylesheet" href="<%= context %>/assets/course.css"></head><body><main class="container">
<a href="<%= context %>/patient">返回患者门户</a><section class="card"><h1>患者病情补充</h1><p>患者自述独立于医生主诉，仅预约中或接诊中可修改。最多500个Unicode码点，可清空。</p>
<% if(request.getAttribute("clinicalError")!=null){ %><p role="alert"><%= value(request,"clinicalError") %></p><% } %>
<% if(complaint!=null){ %><p>最近更新时间：<%= WebEscaper.html(complaint.path("updatedAt").asText("未知")) %> · 版本 <%= WebEscaper.html(complaint.path("revision").asText()) %></p><% } %>
<% if(Boolean.TRUE.equals(request.getAttribute("clinicalWritable"))){ %>
<form method="post" action="<%= context %>/patient/complaint/save"><input type="hidden" name="csrfToken" value="<%= value(request,"csrfToken") %>"><input type="hidden" name="appointmentId" value="<%= value(request,"targetId") %>"><label>期望版本<input name="expectedRevision" value="<%= value(request,"expectedRevision") %>" readonly required></label><label>患者自述<textarea name="description" rows="6"><%= value(request,"description") %></textarea></label><button type="submit">保存病情补充</button></form>
<% }else{ %><p>当前只读：预约已结束、医院不可写或状态尚未确认。</p>
<% if(request.getAttribute("clinicalError")!=null){ %><p>本次输入仅保留在当前页面，可复制留存。</p><% } %>
<label>期望版本<input name="expectedRevision" value="<%= value(request,"expectedRevision") %>" readonly></label>
<label>患者自述<textarea name="description" rows="6" readonly><%= value(request,"description") %></textarea></label><% } %>
</section></main></body></html>
