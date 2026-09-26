<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode" %>
<%@ include file="header.jspf" %>
<section class="card">
  <h2>我的接诊预约</h2>
  <% JsonNode appointments = (JsonNode) request.getAttribute("appointments");
     if (appointments != null && appointments.isArray()) { %>
  <div class="table-wrap"><table><thead><tr><th>预约编号</th><th>患者</th><th>号源</th><th>状态</th><th>操作</th></tr></thead><tbody>
  <% for (JsonNode appointment : appointments) { String id = WebEscaper.html(appointment.path("id").asText()); %>
    <tr><td><%= id %></td><td><%= WebEscaper.html(appointment.path("patientId").asText()) %></td>
    <td><%= WebEscaper.html(appointment.path("slotId").asText()) %></td>
    <td><%= WebEscaper.html(appointment.path("status").asText()) %></td>
    <td>
      <% if ("BOOKED".equals(appointment.path("status").asText())) { %>
      <form method="post" action="<%= context %>/doctor/start">
        <input type="hidden" name="csrfToken" value="<%= request.getAttribute("csrfToken") %>">
        <input type="hidden" name="appointmentId" value="<%= id %>"><button type="submit">开始接诊</button>
      </form>
      <% } else if ("IN_PROGRESS".equals(appointment.path("status").asText())) { %>
      <form method="post" action="<%= context %>/doctor/complete" class="clinical-form">
        <input type="hidden" name="csrfToken" value="<%= request.getAttribute("csrfToken") %>">
        <input type="hidden" name="appointmentId" value="<%= id %>">
        <label>诊断<input name="diagnosis" maxlength="500" required></label>
        <label>建议<textarea name="advice" maxlength="2000" required></textarea></label>
        <button type="submit">完成接诊并生成账单</button>
      </form>
      <% } else { %><span class="subtle">已完成</span><% } %>
    </td></tr>
  <% } %>
  </tbody></table></div>
  <% if (appointments.size() == 0) { %><p class="subtle">暂无待接诊预约。</p><% } %>
  <% } %>
</section>
<%@ include file="footer.jspf" %>
