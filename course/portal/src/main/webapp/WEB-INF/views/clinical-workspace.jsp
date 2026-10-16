<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode,com.medcloud.course.portal.web.WebEscaper" %>
<%! private String value(javax.servlet.http.HttpServletRequest r,String key){Object v=r.getAttribute(key);return WebEscaper.html(v==null?"":String.valueOf(v));} %>
<% String context=request.getContextPath();JsonNode complaint=(JsonNode)request.getAttribute("patientComplaint"); %>
<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>私有接诊草稿</title><link rel="stylesheet" href="<%= context %>/assets/course.css"></head><body><main class="container"><a href="<%= context %>/doctor">返回医生门户</a><section class="card"><h1>私有接诊草稿</h1><p>保存草稿不生成病历或账单，仅原接诊医生可见。完成接诊时提交当前四字段并删除草稿。</p>
<% if(request.getAttribute("clinicalError")!=null){ %><p role="alert"><%= value(request,"clinicalError") %></p><% } %>
<h2>患者自述（独立来源）</h2><% if(complaint!=null){ %><pre><%= WebEscaper.html(complaint.path("description").asText("")) %></pre><p>修改时间 <%= WebEscaper.html(complaint.path("updatedAt").asText("未知")) %> · 版本 <%= WebEscaper.html(complaint.path("revision").asText()) %></p><% } %>
<% if(Boolean.TRUE.equals(request.getAttribute("clinicalWritable"))){ %>
<form method="post" action="<%= context %>/doctor/clinical/save"><input type="hidden" name="csrfToken" value="<%= value(request,"csrfToken") %>"><input type="hidden" name="appointmentId" value="<%= value(request,"targetId") %>"><label>期望草稿版本<input name="expectedRevision" value="<%= value(request,"expectedRevision") %>" readonly required></label>
<label>医生主诉（最多500码点）<textarea name="complaint" rows="4"><%= value(request,"complaint") %></textarea></label><label>诊断（最多500码点，完成时必填）<textarea name="diagnosis"><%= value(request,"diagnosis") %></textarea></label><label>建议（最多2000码点，完成时必填）<textarea name="advice"><%= value(request,"advice") %></textarea></label><label>备注（最多2000码点）<textarea name="notes"><%= value(request,"notes") %></textarea></label>
<button type="submit">保存私有草稿</button><button type="submit" name="copyPatientComplaint" value="1">明确复制患者最新自述到主诉并保存草稿</button><button type="submit" formaction="<%= context %>/doctor/clinical/complete">完成接诊并生成账单</button></form>
<% }else{ %><p>当前只读：尚未开始接诊、已完成或医院不可写。</p>
<% if(request.getAttribute("clinicalError")!=null){ %><p>本次输入仅保留在当前页面，可复制留存。</p><% } %>
<label>期望草稿版本<input name="expectedRevision" value="<%= value(request,"expectedRevision") %>" readonly></label>
<label>医生主诉<textarea name="complaint" readonly><%= value(request,"complaint") %></textarea></label>
<label>诊断<textarea name="diagnosis" readonly><%= value(request,"diagnosis") %></textarea></label>
<label>建议<textarea name="advice" readonly><%= value(request,"advice") %></textarea></label>
<label>备注<textarea name="notes" readonly><%= value(request,"notes") %></textarea></label><% } %>
</section></main></body></html>
