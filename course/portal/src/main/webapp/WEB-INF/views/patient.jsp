<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode" %>
<%@ include file="header.jspf" %>
<section class="card">
  <h2>查询号源</h2>
  <form method="get" action="<%= context %>/patient" class="inline-form">
    <label>医院编号<input name="tenantId" value="<%= request.getAttribute("tenantId") == null ? "" : request.getAttribute("tenantId") %>" placeholder="例如 tenant-a" required></label>
    <label>科室编号<input name="departmentId" value="<%= request.getAttribute("departmentId") == null ? "" : request.getAttribute("departmentId") %>"></label>
    <label>医生编号<input name="doctorId" value="<%= request.getAttribute("doctorId") == null ? "" : request.getAttribute("doctorId") %>"></label>
    <label>日期<input type="date" name="date" value="<%= request.getAttribute("date") == null ? "" : request.getAttribute("date") %>"></label>
    <button type="submit">查询</button>
  </form>
  <% JsonNode slots = (JsonNode) request.getAttribute("slots");
     if (slots != null && slots.isArray()) { %>
  <div class="table-wrap"><table><thead><tr><th>日期</th><th>时段</th><th>科室</th><th>医生</th><th>余量/容量</th><th>诊费</th><th>操作</th></tr></thead><tbody>
  <% for (JsonNode slot : slots) { %>
    <tr>
      <td><%= WebEscaper.html(slot.path("date").asText()) %></td>
      <td><%= WebEscaper.html(slot.path("startTime").asText()) %>–<%= WebEscaper.html(slot.path("endTime").asText()) %></td>
      <td><%= WebEscaper.html(slot.path("departmentId").asText()) %></td>
      <td><%= WebEscaper.html(slot.path("doctorId").asText()) %></td>
      <td><%= WebEscaper.html(slot.path("remaining").asText()) %>/<%= WebEscaper.html(slot.path("capacity").asText()) %></td>
      <td>¥<%= WebEscaper.html(slot.path("fee").asText()) %></td>
      <td><form method="post" action="<%= context %>/patient/book">
        <input type="hidden" name="csrfToken" value="<%= request.getAttribute("csrfToken") %>">
        <input type="hidden" name="slotId" value="<%= WebEscaper.html(slot.path("id").asText()) %>">
        <button type="submit">预约</button>
      </form></td>
    </tr>
  <% } %>
  </tbody></table></div>
  <% if (slots.size() == 0) { %><p class="subtle">当前没有可预约号源。</p><% } %>
  <% } %>
</section>
<section class="card">
  <h2>我的预约历史</h2>
  <% JsonNode appointments = (JsonNode) request.getAttribute("appointments");
     if (appointments != null && appointments.isArray()) { %>
  <div class="table-wrap"><table><thead><tr><th>预约编号</th><th>医院 / 科室 / 医生</th><th>就诊时间</th><th>状态</th><th>操作</th></tr></thead><tbody>
  <% for (JsonNode appointment : appointments) { %>
    <tr><td><%= WebEscaper.html(appointment.path("id").asText()) %></td>
    <td><%= WebEscaper.html(appointment.path("hospitalName").asText()) %> / <%= WebEscaper.html(appointment.path("departmentName").asText()) %> / <%= WebEscaper.html(appointment.path("doctorName").asText()) %></td>
    <td><%= WebEscaper.html(appointment.path("slotDate").asText()) %> <%= WebEscaper.html(appointment.path("startTime").asText()) %>–<%= WebEscaper.html(appointment.path("endTime").asText()) %></td>
    <td><%= WebEscaper.html(appointment.path("status").asText()) %></td>
    <td><% if ("BOOKED".equals(appointment.path("status").asText())) { %>
      <form method="post" action="<%= context %>/patient/cancel">
        <input type="hidden" name="csrfToken" value="<%= request.getAttribute("csrfToken") %>">
        <input type="hidden" name="appointmentId" value="<%= WebEscaper.html(appointment.path("id").asText()) %>">
        <button type="submit">取消预约</button>
      </form><% } %></td></tr>
  <% } %></tbody></table></div>
  <% if (appointments.size() == 0) { %><p class="subtle">暂无预约记录。</p><% } %>
  <% } %>
</section>
<section class="card">
  <h2>查看病历与账单</h2>
  <form method="get" action="<%= context %>/patient" class="inline-form">
    <label>预约编号<input name="appointmentId" value="<%= request.getAttribute("appointmentId") == null ? "" : request.getAttribute("appointmentId") %>" required></label>
    <button type="submit">查看</button>
  </form>
  <% JsonNode record = (JsonNode) request.getAttribute("record");
     JsonNode order = (JsonNode) request.getAttribute("order");
     if (record != null) { %>
    <div class="result"><h3>诊断结果</h3><p><%= WebEscaper.html(record.path("diagnosis").asText()) %></p>
    <h3>医生建议</h3><p><%= WebEscaper.html(record.path("advice").asText()) %></p></div>
  <% } if (order != null) { %>
    <div class="result"><h3>诊疗账单</h3><p>金额：¥<%= WebEscaper.html(order.path("amount").asText()) %> · 状态：<%= WebEscaper.html(order.path("status").asText()) %></p>
    <% if ("UNPAID".equals(order.path("status").asText())) { %>
      <form method="post" action="<%= context %>/patient/pay">
        <input type="hidden" name="csrfToken" value="<%= request.getAttribute("csrfToken") %>">
        <input type="hidden" name="orderId" value="<%= WebEscaper.html(order.path("id").asText()) %>">
        <button type="submit">模拟缴费</button>
      </form>
    <% } %></div>
  <% } %>
</section>
<%@ include file="footer.jspf" %>
