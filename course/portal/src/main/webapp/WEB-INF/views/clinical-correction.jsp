<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.medcloud.course.portal.web.WebEscaper" %>
<%! private String value(javax.servlet.http.HttpServletRequest r,String key){Object v=r.getAttribute(key);return WebEscaper.html(v==null?"":String.valueOf(v));} %>
<% String context=request.getContextPath(); %>
<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>追加病历更正</title><link rel="stylesheet" href="<%= context %>/assets/course.css"></head><body><main class="container"><a href="<%= context %>/doctor/record-details?recordId=<%= value(request,"targetId") %>">查阅原始与有效版本</a><section class="card"><h1>追加病历更正</h1><p>更正保留原始病历、完成时间、附件关联与账单，仅追加可追溯版本。重试必须保留原操作编号、版本及内容。</p>
<% if(request.getAttribute("clinicalError")!=null){ %><p role="alert"><%= value(request,"clinicalError") %></p><% } %>
<% if(Boolean.TRUE.equals(request.getAttribute("clinicalWritable"))){ %>
<form method="post" action="<%= context %>/doctor/correction/append"><input type="hidden" name="csrfToken" value="<%= value(request,"csrfToken") %>"><input type="hidden" name="recordId" value="<%= value(request,"targetId") %>"><label>期望版本<input name="expectedRevision" value="<%= value(request,"expectedRevision") %>" readonly required></label><label>操作编号（重试保留）<input name="operationKey" value="<%= value(request,"operationKey") %>" readonly required></label>
<label>更正后主诉（最多500码点）<textarea name="complaint"><%= value(request,"complaint") %></textarea></label><label>更正后诊断（最多500码点）<textarea name="diagnosis" required><%= value(request,"diagnosis") %></textarea></label><label>更正后建议（最多2000码点）<textarea name="advice" required><%= value(request,"advice") %></textarea></label><label>更正后备注（最多2000码点）<textarea name="notes"><%= value(request,"notes") %></textarea></label><label>更正原因（最多2000码点）<textarea name="reason" required><%= value(request,"reason") %></textarea></label><button type="submit">追加更正</button></form>
<% }else{ %><p>当前只读，医院或账号状态不允许追加更正。</p>
<% if(request.getAttribute("clinicalError")!=null){ %><p>本次输入仅保留在当前页面，可复制留存。</p><% } %>
<label>期望版本<input name="expectedRevision" value="<%= value(request,"expectedRevision") %>" readonly></label>
<label>操作编号（重试保留）<input name="operationKey" value="<%= value(request,"operationKey") %>" readonly></label>
<label>更正后主诉<textarea name="complaint" readonly><%= value(request,"complaint") %></textarea></label>
<label>更正后诊断<textarea name="diagnosis" readonly><%= value(request,"diagnosis") %></textarea></label>
<label>更正后建议<textarea name="advice" readonly><%= value(request,"advice") %></textarea></label>
<label>更正后备注<textarea name="notes" readonly><%= value(request,"notes") %></textarea></label>
<label>更正原因<textarea name="reason" readonly><%= value(request,"reason") %></textarea></label><% } %>
</section></main></body></html>
