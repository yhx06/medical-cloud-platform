<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode" %>
<% String workspace = "doctor", workspaceLabel = "医生接诊", workspaceDescription = "管理接诊预约，记录诊断与建议，查阅已完成的病历。"; %>
<%@ include file="header.jspf" %>
<% JsonNode doctorProfile = (JsonNode) request.getAttribute("doctorProfile");
   String doctorTitle = doctorProfile != null && doctorProfile.path("title").isTextual() ? doctorProfile.path("title").asText() : "";
   String doctorBio = doctorProfile != null && doctorProfile.path("bio").isTextual() ? doctorProfile.path("bio").asText() : ""; %>
<section class="card" id="consultations">
  <div class="section-heading"><div><p class="section-kicker">01 / CONSULTATION</p><h2>接诊工作台</h2></div><span class="section-tag">我的接诊预约</span></div>
  <p class="subtle">开始接诊后填写主诉、诊断与建议，完成时自动生成模拟账单。</p>
  <%= sectionError(request, "appointmentsError") %>
  <% JsonNode appointments = (JsonNode) request.getAttribute("appointments");
     if (appointments != null && appointments.isArray()) { %>
  <div class="table-wrap" tabindex="0" role="region" aria-label="接诊预约表格，左右滚动查看更多"><table><thead><tr><th>预约编号</th><th>患者</th><th>号源</th><th>状态</th><th>操作</th></tr></thead><tbody>
  <% for (JsonNode appointment : appointments) { String id = WebEscaper.html(appointment.path("id").asText()); %>
    <tr><td class="identifier"><%= id %></td><td class="identifier"><%= WebEscaper.html(appointment.path("patientId").asText()) %></td>
    <td class="identifier"><%= WebEscaper.html(appointment.path("slotId").asText()) %></td>
    <td><%= stateBadge(appointment.path("status").asText()) %></td>
    <td>
      <% if(appointment.path("id").asText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")){ %>
      <a href="<%= context %>/doctor/files?appointmentId=<%= id %>">查看患者资料</a>
      <% if("IN_PROGRESS".equals(appointment.path("status").asText()) || "BOOKED".equals(appointment.path("status").asText())){ %><a href="<%= context %>/doctor/clinical?appointmentId=<%= id %>">打开私有接诊草稿与完成工作区</a><% } %><% } %>
      <% if (!workspaceWritable) { %><span class="read-only-reason"><%= readOnlyReason(hospitalContext == null ? "" : hospitalContext.path("status").asText()) %></span>
      <% } else if ("BOOKED".equals(appointment.path("status").asText())) { %>
      <form method="post" action="<%= context %>/doctor/start">
        <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request, "csrfToken")) %>">
        <input type="hidden" name="appointmentId" value="<%= id %>"><button type="submit">开始接诊</button>
      </form>
      <% } else if ("IN_PROGRESS".equals(appointment.path("status").asText())) { %>
      <span class="subtle">请进入接诊工作区保存草稿或完成诊疗</span>
      <% } else if ("CANCELED".equals(appointment.path("status").asText())) { %><span class="subtle">已取消</span>
      <% } else { %><span class="subtle">已完成</span><% } %>
    </td></tr>
  <% } %>
  </tbody></table></div>
  <% if (appointments.size() == 0) { %><p class="empty-state">暂无接诊预约。</p><% } %>
  <% JsonNode appointmentsPage = (JsonNode) request.getAttribute("appointmentsPage");
     if (appointmentsPage != null) {
       long aNum = appointmentsPage.path("page").asLong(), aSize = appointmentsPage.path("size").asLong(), aTotal = appointmentsPage.path("total").asLong();
       long aPages = aTotal / aSize + (aTotal % aSize == 0 ? 0 : 1); %>
  <nav class="pagination" aria-label="接诊预约分页"><span>共 <%= aTotal %> 条 · 第 <%= aNum %> / <%= aPages %> 页</span>
    <% if (aNum > 1) { %><a href="<%= filteredUrl(context + "/doctor", joinFilters("", "page=" + (aNum - 1)), "consultations") %>">上一页</a><% } %>
    <% if (aNum < aPages && aNum * aSize <= Integer.MAX_VALUE) { %><a href="<%= filteredUrl(context + "/doctor", joinFilters("", "page=" + (aNum + 1)), "consultations") %>">下一页</a><% } %>
  </nav>
  <% } %>
  <% } %>
</section>
<section class="card" id="clinical-history">
  <div class="section-heading"><div><p class="section-kicker">02 / CLINICAL RECORDS</p><h2>我的病历</h2></div></div>
  <p class="subtle" id="record-filter-help">此表展示原始病历；当前有效版本及更正历史请打开详情。按接诊完成日期筛选；起止日期可留空，开始日期不得晚于结束日期。</p>
  <%= sectionError(request, "recordsError") %>
  <form method="get" action="<%= context %>/doctor#clinical-history" class="inline-form" aria-describedby="record-filter-help">
    <label>开始日期<input type="date" name="from" value="<%= request.getAttribute("from") == null ? "" : request.getAttribute("from") %>"></label>
    <label>结束日期<input type="date" name="to" value="<%= request.getAttribute("to") == null ? "" : request.getAttribute("to") %>"></label>
    <button type="submit">筛选</button>
    <a class="reset-link" href="<%= context %>/doctor#clinical-history">重置筛选</a>
  </form>
  <% JsonNode records = (JsonNode) request.getAttribute("records");
     if (records != null && records.isArray()) { %>
  <div class="table-wrap" tabindex="0" role="region" aria-label="病历记录表格，左右滚动查看更多"><table><thead><tr><th>完成时间</th><th>预约编号</th><th>患者</th><th>主诉</th><th>诊断</th><th>建议</th><th>备注</th></tr></thead><tbody>
  <% for (JsonNode record : records) { %>
    <tr><td><%= WebEscaper.html(record.path("completedAt").isNull() ? "历史时间未知" : record.path("completedAt").asText("历史时间未知")) %></td>
    <td class="identifier"><%= WebEscaper.html(record.path("appointmentId").asText()) %></td>
    <td class="identifier"><%= WebEscaper.html(record.path("patientId").asText()) %></td>
    <td><%= WebEscaper.html(record.path("complaint").asText("")) %></td>
    <td><%= WebEscaper.html(record.path("diagnosis").asText()) %></td>
    <td><%= WebEscaper.html(record.path("advice").asText()) %></td>
    <td><%= WebEscaper.html(record.path("notes").asText("")) %>
      <% if(record.path("id").asText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")){ %>
      <a href="<%= context %>/doctor/record-details?recordId=<%= WebEscaper.html(record.path("id").asText()) %>">原始、有效病历与更正历史</a>
      <a href="<%= context %>/doctor/files?recordId=<%= WebEscaper.html(record.path("id").asText()) %>">查阅或追加病历附件</a><% } %></td></tr>
  <% } %>
  </tbody></table></div>
  <% if (records.size() == 0) { %><p class="empty-state">暂无符合条件的病历，已完成接诊的记录会显示在这里。</p><% } %>
  <% JsonNode recordsPage = (JsonNode) request.getAttribute("recordsPage");
     if (recordsPage != null) {
       String recordsFilters = orderFilters(request);
       long rNum = recordsPage.path("page").asLong(), rSize = recordsPage.path("size").asLong(), rTotal = recordsPage.path("total").asLong();
       long rPages = rTotal / rSize + (rTotal % rSize == 0 ? 0 : 1); %>
  <nav class="pagination" aria-label="病历分页"><span>共 <%= rTotal %> 条 · 第 <%= rNum %> / <%= rPages %> 页</span>
    <% if (rNum > 1) { %><a href="<%= filteredUrl(context + "/doctor", joinFilters(recordsFilters, "page=" + (rNum - 1)), "clinical-history") %>">上一页</a><% } %>
    <% if (rNum < rPages && rNum * rSize <= Integer.MAX_VALUE) { %><a href="<%= filteredUrl(context + "/doctor", joinFilters(recordsFilters, "page=" + (rNum + 1)), "clinical-history") %>">下一页</a><% } %>
  </nav>
  <% } %>
  <% } %>
</section>
<section class="card" id="doctor-profile">
  <div class="section-heading"><div><p class="section-kicker">03 / PROFILE</p><h2>个人资料</h2></div></div>
  <%= sectionError(request, "doctorProfileError") %>
  <% if (doctorProfile != null) { %>
  <p>当前职称：<strong><%= WebEscaper.html(doctorTitle.isEmpty() ? "未填写" : doctorTitle) %></strong></p>
  <form method="post" action="<%= context %>/doctor/profile" class="stacked-form">
    <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request, "csrfToken")) %>">
    <label>职称<input name="title" maxlength="100" value="<%= WebEscaper.html(doctorTitle) %>"></label>
    <label>简介<textarea name="bio" maxlength="1000" rows="3"><%= WebEscaper.html(doctorBio) %></textarea></label>
    <button type="submit">保存资料</button>
  </form>
  <% } %>
</section>
<%@ include file="footer.jspf" %>
