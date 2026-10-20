<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode" %>
<%@ page import="java.net.URLEncoder" %>
<%!
  private String directoryName(JsonNode options, String id, String fallback) {
    if (options != null && options.isArray())
      for (JsonNode option : options) if (id.equals(option.path("id").asText()))
        return WebEscaper.html(option.path("name").asText());
    return fallback;
  }
%>
<% String workspace = "patient", workspaceLabel = "患者服务", workspaceDescription = "查询号源、管理预约，查看您的诊疗记录与账单。"; %>
<%@ include file="header.jspf" %>
<% JsonNode hospitals = (JsonNode) request.getAttribute("hospitals");
   JsonNode departments = (JsonNode) request.getAttribute("departments");
   JsonNode doctors = (JsonNode) request.getAttribute("doctors");
   String selectedTenant = request.getAttribute("tenantId") == null ? "" : String.valueOf(request.getAttribute("tenantId"));
   String selectedDepartment = request.getAttribute("departmentId") == null ? "" : String.valueOf(request.getAttribute("departmentId"));
   String selectedDoctor = request.getAttribute("doctorId") == null ? "" : String.valueOf(request.getAttribute("doctorId"));
   String selectedDate = request.getAttribute("date") == null ? "" : String.valueOf(request.getAttribute("date"));
   String filterQuery = request.getAttribute("patientFilters") == null ? "" : String.valueOf(request.getAttribute("patientFilters")); %>
<section class="card" id="slot-search">
  <div class="section-heading"><div><p class="section-kicker">01 / APPOINTMENT</p><h2>预约挂号</h2></div><span class="section-tag">查询可预约号源</span></div>
  <p class="subtle" id="directory-help">先选择医院，再按科室、医生和日期筛选。更换医院或科室后将清空下级选择；未启用脚本时，请点击“更新选项”。</p>
  <%= sectionError(request, "slotsError") %>
  <form method="get" action="<%= context %>/patient#slot-search" class="inline-form" data-directory-form aria-describedby="directory-help">
    <input type="hidden" name="previousTenantId" value="<%= selectedTenant %>">
    <input type="hidden" name="previousDepartmentId" value="<%= selectedDepartment %>">
    <label>医院<select name="tenantId" required>
      <option value="">请选择医院</option>
      <% if (hospitals != null && hospitals.isArray()) for (JsonNode option : hospitals) { %>
      <option value="<%= WebEscaper.html(option.path("id").asText()) %>"<%= WebEscaper.html(option.path("id").asText()).equals(selectedTenant) ? " selected" : "" %>><%= WebEscaper.html(option.path("name").asText()) %></option>
      <% } %>
    </select></label>
    <label>科室<select name="departmentId"<%= selectedTenant.isEmpty() ? " disabled" : "" %>>
      <option value="">全部科室</option>
      <% if (departments != null && departments.isArray()) for (JsonNode option : departments) { %>
      <option value="<%= WebEscaper.html(option.path("id").asText()) %>"<%= WebEscaper.html(option.path("id").asText()).equals(selectedDepartment) ? " selected" : "" %>><%= WebEscaper.html(option.path("name").asText()) %></option>
      <% } %>
    </select></label>
    <label>医生<select name="doctorId"<%= selectedTenant.isEmpty() ? " disabled" : "" %>>
      <option value="">全部医生</option>
      <% if (doctors != null && doctors.isArray()) for (JsonNode option : doctors) { %>
      <option value="<%= WebEscaper.html(option.path("id").asText()) %>"<%= WebEscaper.html(option.path("id").asText()).equals(selectedDoctor) ? " selected" : "" %>><%= WebEscaper.html(option.path("name").asText()) %></option>
      <% } %>
    </select></label>
    <label>日期<input type="date" name="date" value="<%= selectedDate %>"></label>
    <button type="submit">查询号源</button>
    <button type="submit" class="button--secondary" name="refreshDirectory" value="1" formnovalidate>更新选项</button>
    <a class="reset-link" href="<%= context %>/patient#slot-search">重置筛选</a>
  </form>
  <% JsonNode slots = (JsonNode) request.getAttribute("slots");
     if (slots != null && slots.isArray()) { %>
  <div class="table-wrap" tabindex="0" role="region" aria-label="可预约号源表格，左右滚动查看更多"><table><thead><tr><th>日期</th><th>时段</th><th>科室</th><th>医生</th><th>余量/容量</th><th>诊费</th><th>操作</th></tr></thead><tbody>
  <% for (JsonNode slot : slots) { %>
    <tr>
      <td><%= WebEscaper.html(slot.path("date").asText()) %></td>
      <td><%= WebEscaper.html(slot.path("startTime").asText()) %>–<%= WebEscaper.html(slot.path("endTime").asText()) %></td>
      <td><%= directoryName(departments, slot.path("departmentId").asText(), "科室暂不可用") %></td>
      <td><%= directoryName(doctors, slot.path("doctorId").asText(), "医生暂不可用") %></td>
      <td><%= WebEscaper.html(slot.path("remaining").asText()) %>/<%= WebEscaper.html(slot.path("capacity").asText()) %></td>
      <td class="money">¥<%= WebEscaper.html(slot.path("fee").asText()) %></td>
      <td><form method="post" action="<%= context %>/patient/book">
        <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request, "csrfToken")) %>">
        <input type="hidden" name="slotId" value="<%= WebEscaper.html(slot.path("id").asText()) %>">
        <input type="hidden" name="tenantId" value="<%= selectedTenant %>">
        <input type="hidden" name="departmentId" value="<%= selectedDepartment %>">
        <input type="hidden" name="doctorId" value="<%= selectedDoctor %>">
        <input type="hidden" name="date" value="<%= selectedDate %>">
        <button type="submit">预约</button>
      </form></td>
    </tr>
  <% } %>
  </tbody></table></div>
  <% if (slots.size() == 0) { %><p class="empty-state">当前没有可预约号源，可调整筛选条件后重试。</p><% } %>
  <% } else { %><p class="empty-state">从查询开始，选择适合您的就诊时段。</p><% } %>
</section>
<section class="card" id="appointment-history">
  <div class="section-heading"><div><p class="section-kicker">02 / MY APPOINTMENTS</p><h2>我的预约历史</h2></div></div>
  <%= sectionError(request, "appointmentsError") %>
  <% JsonNode appointments = (JsonNode) request.getAttribute("appointments");
     if (appointments != null && appointments.isArray()) { %>
  <div class="table-wrap" tabindex="0" role="region" aria-label="预约历史表格，左右滚动查看更多"><table><thead><tr><th>预约编号</th><th>医院 / 科室 / 医生</th><th>就诊时间</th><th>状态</th><th>操作</th></tr></thead><tbody>
  <% for (JsonNode appointment : appointments) { %>
    <tr><td class="identifier"><%= WebEscaper.html(appointment.path("id").asText()) %></td>
    <td><%= WebEscaper.html(appointment.path("hospitalName").asText()) %> / <%= WebEscaper.html(appointment.path("departmentName").asText()) %> / <%= WebEscaper.html(appointment.path("doctorName").asText()) %></td>
    <td><%= visitDateLabel(appointment) %> <%= WebEscaper.html(appointment.path("startTime").asText()) %>–<%= WebEscaper.html(appointment.path("endTime").asText()) %></td>
    <td><%= stateBadge(appointment.path("status").asText()) %></td>
    <td><% if ("BOOKED".equals(appointment.path("status").asText())
        && "ACTIVE".equals(appointment.path("tenantStatus").asText()) && appointment.path("businessWritable").isBoolean()
        && appointment.path("businessWritable").asBoolean() && beforeCancellationCutoff(appointment)) { %>
      <form method="post" action="<%= context %>/patient/cancel">
        <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request, "csrfToken")) %>">
        <input type="hidden" name="appointmentId" value="<%= WebEscaper.html(appointment.path("id").asText()) %>">
        <button type="submit" class="button--danger">取消预约</button>
      </form><% } else {
        if (!"ACTIVE".equals(appointment.path("tenantStatus").asText()) || !appointment.path("businessWritable").isBoolean()
            || !appointment.path("businessWritable").asBoolean()) { %>
      <p class="read-only-reason"><%= readOnlyReason(appointment.path("tenantStatus").asText()) %></p>
      <% } else if ("BOOKED".equals(appointment.path("status").asText())) { %><span class="subtle">已到取消截止时间或就诊时间暂无法确认</span>
      <% } if ("COMPLETED".equals(appointment.path("status").asText())) {
        String recordUrl = context + "/patient?" + (filterQuery.isEmpty() ? "" : filterQuery + "&")
          + "appointmentId=" + URLEncoder.encode(appointment.path("id").asText(), "UTF-8") + "#record-billing"; %>
      <a class="record-link" href="<%= WebEscaper.html(recordUrl) %>">查看病历与账单</a>
      <% } } %>
      <% if(appointment.path("id").asText().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")){ %>
      <a href="<%= context %>/patient/complaint?appointmentId=<%= WebEscaper.html(appointment.path("id").asText()) %>">补充或查阅病情</a>
      <% if("COMPLETED".equals(appointment.path("status").asText())){ %><a href="<%= context %>/patient/record-details?appointmentId=<%= WebEscaper.html(appointment.path("id").asText()) %>">查看原始、有效病历与更正历史</a><% } %>
      <a href="<%= context %>/patient/files?appointmentId=<%= WebEscaper.html(appointment.path("id").asText()) %>">查看诊疗附件</a><% } %></td></tr>
  <% } %></tbody></table></div>
  <% if (appointments.size() == 0) { %><p class="empty-state">暂无预约记录，完成挂号后可在这里查看。</p><% } %>
  <% } %>
</section>
<section class="card" id="record-billing">
  <div class="section-heading"><div><p class="section-kicker">03 / RECORDS & BILLING</p><h2>病历与账单</h2></div><span class="section-tag">模拟诊疗服务</span></div>
  <p class="subtle">点击上方已完成预约的“查看病历与账单”即可查询；也可手动输入本人已完成预约的编号。</p>
  <%= sectionError(request, "recordError") %>
  <%= sectionError(request, "orderError") %>
  <form method="get" action="<%= context %>/patient#record-billing" class="inline-form">
    <input type="hidden" name="tenantId" value="<%= selectedTenant %>">
    <input type="hidden" name="departmentId" value="<%= selectedDepartment %>">
    <input type="hidden" name="doctorId" value="<%= selectedDoctor %>">
    <input type="hidden" name="date" value="<%= selectedDate %>">
    <label>预约编号<input name="appointmentId" value="<%= request.getAttribute("appointmentId") == null ? "" : request.getAttribute("appointmentId") %>" required></label>
    <button type="submit">查看</button>
  </form>
  <% JsonNode record = (JsonNode) request.getAttribute("record");
     JsonNode order = (JsonNode) request.getAttribute("order");
     if (record != null) { %>
    <div class="result"><h3>原始诊断结果</h3><p>当前有效版本与更正历史请打开预约的病历详情。</p><p><%= WebEscaper.html(record.path("diagnosis").asText()) %></p>
    <h3>医生建议</h3><p><%= WebEscaper.html(record.path("advice").asText()) %></p></div>
  <% } if (order != null) { %>
    <div class="result"><h3>诊疗账单</h3><p><span class="money">¥<%= WebEscaper.html(order.path("amount").asText()) %></span> · <%= stateBadge(order.path("status").asText()) %></p>
    <% if ("PAID".equals(order.path("status").asText())) { %>
      <p>缴费时间：<%= WebEscaper.html(order.path("paidAt").isNull() ? "历史时间未知" : order.path("paidAt").asText("历史时间未知")) %></p>
    <% } %>
    <% if (!order.path("paymentReference").isNull() && !order.path("paymentReference").asText().isEmpty()) { %>
      <p>支付流水号：<%= WebEscaper.html(order.path("paymentReference").asText()) %></p>
    <% } %>
    <% if ("UNPAID".equals(order.path("status").asText()) && order.path("businessWritable").isBoolean()
        && order.path("businessWritable").asBoolean()) { %>
      <form method="post" action="<%= context %>/patient/pay">
        <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request, "csrfToken")) %>">
        <input type="hidden" name="orderId" value="<%= WebEscaper.html(order.path("id").asText()) %>">
        <button type="submit">模拟缴费</button>
      </form>
    <% } else if (!order.path("businessWritable").isBoolean() || !order.path("businessWritable").asBoolean()) { %>
      <p class="read-only-reason"><%= readOnlyReason("") %></p>
    <% } %></div>
  <% } %>
</section>
<%@ include file="footer.jspf" %>
