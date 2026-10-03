<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode" %>
<% String workspace = "doctor", workspaceLabel = "医生接诊", workspaceDescription = "管理接诊预约，记录诊断与建议，查阅已完成的病历。"; %>
<%@ include file="header.jspf" %>
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
      <% if (!workspaceWritable) { %><span class="read-only-reason"><%= readOnlyReason(hospitalContext == null ? "" : hospitalContext.path("status").asText()) %></span>
      <% } else if ("BOOKED".equals(appointment.path("status").asText())) { %>
      <form method="post" action="<%= context %>/doctor/start">
        <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request, "csrfToken")) %>">
        <input type="hidden" name="appointmentId" value="<%= id %>"><button type="submit">开始接诊</button>
      </form>
      <% } else if ("IN_PROGRESS".equals(appointment.path("status").asText())) { %>
      <form method="post" action="<%= context %>/doctor/complete" class="clinical-form">
        <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request, "csrfToken")) %>">
        <input type="hidden" name="appointmentId" value="<%= id %>">
        <label>主诉<textarea name="complaint" maxlength="500" required></textarea></label>
        <label>诊断<input name="diagnosis" maxlength="500" required></label>
        <label>建议<textarea name="advice" maxlength="2000" required></textarea></label>
        <label>备注<textarea name="notes" maxlength="2000"></textarea></label>
        <button type="submit">完成接诊并生成账单</button>
      </form>
      <% } else if ("CANCELED".equals(appointment.path("status").asText())) { %><span class="subtle">已取消</span>
      <% } else { %><span class="subtle">已完成</span><% } %>
    </td></tr>
  <% } %>
  </tbody></table></div>
  <% if (appointments.size() == 0) { %><p class="empty-state">暂无接诊预约。</p><% } %>
  <% } %>
</section>
<section class="card" id="clinical-history">
  <div class="section-heading"><div><p class="section-kicker">02 / CLINICAL RECORDS</p><h2>我的病历</h2></div></div>
  <p class="subtle" id="record-filter-help">按接诊完成日期筛选；起止日期可留空，开始日期不得晚于结束日期。</p>
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
    <td><%= WebEscaper.html(record.path("notes").asText("")) %></td></tr>
  <% } %>
  </tbody></table></div>
  <% if (records.size() == 0) { %><p class="empty-state">暂无符合条件的病历，已完成接诊的记录会显示在这里。</p><% } %>
  <% } %>
</section>
<%@ include file="footer.jspf" %>
