<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode,com.medcloud.course.portal.web.WebEscaper" %>
<%! private String attachmentText(javax.servlet.http.HttpServletRequest r,String key){Object value=r.getAttribute(key);return WebEscaper.html(value==null?"":String.valueOf(value));} %>
<% String context=request.getContextPath(), home=attachmentText(request,"attachmentHome");
   boolean writable=Boolean.TRUE.equals(request.getAttribute("attachmentWritable"));
   JsonNode pageData=(JsonNode)request.getAttribute("attachmentPage");
   long max=request.getAttribute("attachmentMaxBytes") instanceof Number?((Number)request.getAttribute("attachmentMaxBytes")).longValue():10485760L;
%>
<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="referrer" content="no-referrer"><title>诊疗附件</title><link rel="stylesheet" href="<%= context %>/assets/course.css"></head>
<body><main class="page-shell" id="main-content"><a href="<%= context %><%= home %>">返回工作台</a>
<section class="card"><h1>诊疗附件</h1><p>仅上传虚构或脱敏的 JPG/JPEG、PNG、PDF。<%= max==10485760L?"10 MB（10,485,760 字节）":WebEscaper.html(String.format(java.util.Locale.ROOT,"%,d 字节",max)) %>以内；文件格式校验不等同于杀毒。</p>
<% if(request.getAttribute("attachmentNotice")!=null){ %><p role="status"><%= attachmentText(request,"attachmentNotice") %></p><% } %>
<% if(request.getAttribute("attachmentError")!=null){ %><p role="alert"><%= attachmentText(request,"attachmentError") %></p><% } %>
<% if(request.getAttribute("uploadId")!=null){ %><div class="result"><p>保留原上传编号：<%= attachmentText(request,"uploadId") %>。结果待确认时请查询，不要另建上传。</p>
<form method="post" action="<%= context %>/files/upload-status"><input type="hidden" name="csrfToken" value="<%= attachmentText(request,"csrfToken") %>"><button type="submit">查询原上传结果</button></form></div><% } %>
<% if(writable){ %><form method="post" action="<%= context %><%= home %>/files/upload" enctype="multipart/form-data">
<input type="hidden" name="csrfToken" value="<%= attachmentText(request,"csrfToken") %>"><input type="hidden" name="targetId" value="<%= attachmentText(request,"targetId") %>"><input type="hidden" name="uploadKey" value="<%= attachmentText(request,"uploadKey") %>">
<label>选择附件<input type="file" name="file" accept=".jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf" required></label>
<p>上传或重试后页面会显示结果。结果待确认时，重试请重新选择同一文件；医生附件为病历追加，不覆盖原记录。</p><button type="submit">上传附件</button></form>
<% } else if(pageData!=null){ %><p class="read-only-reason">当前仅可查阅历史附件：医院冻结、预约状态不允许、病历尚未完成，或另一项上传尚待确认。</p><% } %>
</section>
<% if(pageData!=null){long pageNumber=pageData.path("page").asLong(),size=pageData.path("pageSize").asLong(),total=pageData.path("total").asLong(); %>
<section class="card"><h2>可用附件</h2>
<% if(pageData.path("items").size()==0){ %><p class="empty-state">当前页暂无附件。</p><% } else { %>
<div class="table-wrap" tabindex="0" role="region" aria-label="附件列表，左右滚动查看更多"><table><thead><tr><th>文件名</th><th>来源</th><th>类型与大小</th><th>操作</th></tr></thead><tbody>
<% for(JsonNode file:pageData.path("items")){String id=WebEscaper.html(file.path("fileId").asText()); %>
<tr><td><%= WebEscaper.html(file.path("fileName").asText()) %></td><td><%= "PATIENT_APPOINTMENT".equals(file.path("sourceType").asText())?"患者资料":"医生病历附件" %></td><td><%= WebEscaper.html(file.path("contentType").asText()) %> · <%= file.path("sizeBytes").asLong() %> 字节</td><td>
<form method="post" action="<%= context %>/files/download"><input type="hidden" name="csrfToken" value="<%= attachmentText(request,"csrfToken") %>"><input type="hidden" name="fileId" value="<%= id %>"><button type="submit">申请下载</button></form>
<% if(writable&&Boolean.TRUE.equals(request.getAttribute("attachmentDeleteAllowed"))&&"PATIENT_APPOINTMENT".equals(file.path("sourceType").asText())){ %>
<form method="post" action="<%= context %>/patient/files/delete"><input type="hidden" name="csrfToken" value="<%= attachmentText(request,"csrfToken") %>"><input type="hidden" name="appointmentId" value="<%= attachmentText(request,"appointmentId") %>"><input type="hidden" name="fileId" value="<%= id %>"><button type="submit" class="button--danger">移出资料</button></form><% } %>
</td></tr><% } %></tbody></table></div><% } %>
<nav class="pagination" aria-label="附件分页"><span>共 <%= total %> 条 · 第 <%= pageNumber %> 页</span>
<% if(pageNumber>1){ %><a href="<%= context %><%= home %>/files?appointmentId=<%= attachmentText(request,"appointmentId") %>&amp;page=<%= pageNumber-1 %>&amp;pageSize=<%= size %>">上一页</a><% } %>
<% if(pageNumber<Integer.MAX_VALUE&&pageNumber*size<total&&pageNumber*size<=Integer.MAX_VALUE){ %><a href="<%= context %><%= home %>/files?appointmentId=<%= attachmentText(request,"appointmentId") %>&amp;page=<%= pageNumber+1 %>&amp;pageSize=<%= size %>">下一页</a><% } %>
</nav></section><% } %></main></body></html>
