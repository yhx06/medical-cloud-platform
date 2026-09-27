<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode" %>
<%@ include file="header.jspf" %>
<% JsonNode departments = (JsonNode) request.getAttribute("departments");
   JsonNode doctors = (JsonNode) request.getAttribute("doctors");
   JsonNode slots = (JsonNode) request.getAttribute("slots");
   String csrf = WebEscaper.html(String.valueOf(request.getAttribute("csrfToken"))); %>
<section class="card">
  <h2>科室维护</h2>
  <p class="subtle">停用有未来有效号源或未完成预约的科室会被拒绝。</p>
  <form method="post" action="<%= context %>/hospital/department/create" class="inline-form">
    <input type="hidden" name="csrfToken" value="<%= csrf %>">
    <label>新科室名称<input name="name" maxlength="100" required></label>
    <button type="submit">新增科室</button>
  </form>
  <% if (departments != null && departments.isArray()) { %>
  <div class="table-wrap"><table><thead><tr><th>科室编号</th><th>名称与状态</th><th>操作</th></tr></thead><tbody>
    <% for (JsonNode department : departments) {
         String id = WebEscaper.html(department.path("id").asText());
         String status = department.path("status").asText(); %>
    <tr><td><%= id %></td><td><%= WebEscaper.html(department.path("name").asText()) %> · <%= WebEscaper.html(status) %></td>
    <td><form method="post" action="<%= context %>/hospital/department/edit" class="clinical-form">
      <input type="hidden" name="csrfToken" value="<%= csrf %>">
      <input type="hidden" name="id" value="<%= id %>">
      <label>名称<input name="name" maxlength="100" value="<%= WebEscaper.html(department.path("name").asText()) %>" required></label>
      <label>状态<select name="status"><option value="ACTIVE" <%= "ACTIVE".equals(status) ? "selected" : "" %>>启用</option><option value="DISABLED" <%= "DISABLED".equals(status) ? "selected" : "" %>>停用</option></select></label>
      <button type="submit">保存科室</button>
    </form></td></tr>
    <% } %>
  </tbody></table></div>
  <% if (departments.size() == 0) { %><p class="subtle">尚无科室。</p><% } %>
  <% } %>
</section>
<section class="card">
  <h2>医生维护</h2>
  <% if (request.getAttribute("initialPassword") != null) { %>
  <p class="notice">新医生的一次性初始密码：<strong><%= WebEscaper.html(String.valueOf(request.getAttribute("initialPassword"))) %></strong>。请立即妥善记录，离开此页后不会再次显示。</p>
  <% } %>
  <p class="subtle">医生只能归属本院启用科室。有未完成预约时不能停用。</p>
  <form method="post" action="<%= context %>/hospital/doctor/create" class="inline-form">
    <input type="hidden" name="csrfToken" value="<%= csrf %>">
    <label>登录名<input name="username" pattern="[A-Za-z0-9_]{3,64}" maxlength="64" required></label>
    <label>显示名<input name="displayName" maxlength="100" required></label>
    <label>所属科室<select name="departmentId" required><option value="">请选择</option>
      <% if (departments != null && departments.isArray()) for (JsonNode department : departments) {
           if ("ACTIVE".equals(department.path("status").asText())) { %>
      <option value="<%= WebEscaper.html(department.path("id").asText()) %>"><%= WebEscaper.html(department.path("name").asText()) %></option>
      <% } } %>
    </select></label>
    <button type="submit">创建医生</button>
  </form>
  <% if (doctors != null && doctors.isArray()) { %>
  <div class="table-wrap"><table><thead><tr><th>账号</th><th>显示名与科室</th><th>操作</th></tr></thead><tbody>
    <% for (JsonNode doctor : doctors) {
         String id = WebEscaper.html(doctor.path("userId").asText());
         String status = doctor.path("status").asText();
         String selectedDepartment = doctor.path("departmentId").asText(); %>
    <tr><td><%= WebEscaper.html(doctor.path("username").asText()) %><br><span class="subtle"><%= id %></span></td>
    <td><%= WebEscaper.html(doctor.path("displayName").asText()) %><br><span class="subtle"><%= WebEscaper.html(status) %></span></td>
    <td><form method="post" action="<%= context %>/hospital/doctor/edit" class="clinical-form">
      <input type="hidden" name="csrfToken" value="<%= csrf %>">
      <input type="hidden" name="id" value="<%= id %>">
      <label>显示名<input name="displayName" maxlength="100" value="<%= WebEscaper.html(doctor.path("displayName").asText()) %>" required></label>
      <label>所属科室<select name="departmentId" required>
        <% if (departments != null && departments.isArray()) for (JsonNode department : departments) {
             String departmentId = department.path("id").asText();
             if ("ACTIVE".equals(department.path("status").asText()) || departmentId.equals(selectedDepartment)) { %>
        <option value="<%= WebEscaper.html(departmentId) %>" <%= departmentId.equals(selectedDepartment) ? "selected" : "" %>><%= WebEscaper.html(department.path("name").asText()) %></option>
        <% } } %>
      </select></label>
      <label>状态<select name="status"><option value="ACTIVE" <%= "ACTIVE".equals(status) ? "selected" : "" %>>启用</option><option value="DISABLED" <%= "DISABLED".equals(status) ? "selected" : "" %>>停用</option></select></label>
      <button type="submit">保存医生</button>
    </form></td></tr>
    <% } %>
  </tbody></table></div>
  <% if (doctors.size() == 0) { %><p class="subtle">尚无医生。</p><% } %>
  <% } %>
</section>
<section class="card">
  <h2>排班号源</h2>
  <p class="subtle">发布未来号源；已有有效预约的号源不能撤回或变更医生、科室、日期、时段和费用，也不能减少容量。</p>
  <form method="post" action="<%= context %>/hospital/slot/create" class="inline-form">
    <input type="hidden" name="csrfToken" value="<%= csrf %>">
    <label>科室<select name="departmentId" required><option value="">请选择</option>
      <% if (departments != null && departments.isArray()) for (JsonNode department : departments) {
           if ("ACTIVE".equals(department.path("status").asText())) { %>
      <option value="<%= WebEscaper.html(department.path("id").asText()) %>"><%= WebEscaper.html(department.path("name").asText()) %></option>
      <% } } %>
    </select></label>
    <label>医生<select name="doctorId" required><option value="">请选择</option>
      <% if (doctors != null && doctors.isArray()) for (JsonNode doctor : doctors) {
           if ("ACTIVE".equals(doctor.path("status").asText())) { %>
      <option value="<%= WebEscaper.html(doctor.path("userId").asText()) %>"><%= WebEscaper.html(doctor.path("displayName").asText()) %>（<%= WebEscaper.html(doctor.path("departmentId").asText()) %>）</option>
      <% } } %>
    </select></label>
    <label>日期<input type="date" name="date" required></label>
    <label>开始<input type="time" name="startTime" required></label>
    <label>结束<input type="time" name="endTime" required></label>
    <label>容量<input type="number" name="capacity" min="1" required></label>
    <label>诊费<input type="number" name="fee" min="0" step="0.01" required></label>
    <button type="submit">发布号源</button>
  </form>
  <% if (slots != null && slots.isArray()) { %>
  <div class="table-wrap"><table><thead><tr><th>号源编号</th><th>排班与状态</th><th>编辑</th></tr></thead><tbody>
    <% for (JsonNode slot : slots) {
         String slotId = WebEscaper.html(slot.path("id").asText());
         String slotStatus = slot.path("status").asText();
         String slotDepartment = slot.path("departmentId").asText();
         String slotDoctor = slot.path("doctorId").asText(); %>
    <tr><td><%= slotId %></td><td><%= WebEscaper.html(slot.path("date").asText()) %><br>
      <%= WebEscaper.html(slot.path("startTime").asText()) %>–<%= WebEscaper.html(slot.path("endTime").asText()) %><br>
      <%= WebEscaper.html(slotStatus) %></td>
    <td><form method="post" action="<%= context %>/hospital/slot/edit" class="clinical-form">
      <input type="hidden" name="csrfToken" value="<%= csrf %>">
      <input type="hidden" name="id" value="<%= slotId %>">
      <label>科室<select name="departmentId" required>
        <% if (departments != null && departments.isArray()) for (JsonNode department : departments) {
             String departmentId = department.path("id").asText();
             if ("ACTIVE".equals(department.path("status").asText()) || departmentId.equals(slotDepartment)) { %>
        <option value="<%= WebEscaper.html(departmentId) %>" <%= departmentId.equals(slotDepartment) ? "selected" : "" %>><%= WebEscaper.html(department.path("name").asText()) %></option>
        <% } } %>
      </select></label>
      <label>医生<select name="doctorId" required>
        <% if (doctors != null && doctors.isArray()) for (JsonNode doctor : doctors) {
             String doctorId = doctor.path("userId").asText();
             if ("ACTIVE".equals(doctor.path("status").asText()) || doctorId.equals(slotDoctor)) { %>
        <option value="<%= WebEscaper.html(doctorId) %>" <%= doctorId.equals(slotDoctor) ? "selected" : "" %>><%= WebEscaper.html(doctor.path("displayName").asText()) %></option>
        <% } } %>
      </select></label>
      <label>日期<input type="date" name="date" value="<%= WebEscaper.html(slot.path("date").asText()) %>" required></label>
      <label>开始<input type="time" name="startTime" value="<%= WebEscaper.html(slot.path("startTime").asText()) %>" required></label>
      <label>结束<input type="time" name="endTime" value="<%= WebEscaper.html(slot.path("endTime").asText()) %>" required></label>
      <label>容量<input type="number" name="capacity" min="1" value="<%= WebEscaper.html(slot.path("capacity").asText()) %>" required></label>
      <label>诊费<input type="number" name="fee" min="0" step="0.01" value="<%= WebEscaper.html(slot.path("fee").asText()) %>" required></label>
      <label>状态<select name="status"><option value="PUBLISHED" <%= "PUBLISHED".equals(slotStatus) ? "selected" : "" %>>已发布</option><option value="WITHDRAWN" <%= "WITHDRAWN".equals(slotStatus) ? "selected" : "" %>>撤回</option></select></label>
      <button type="submit">保存号源</button>
    </form></td></tr>
    <% } %>
  </tbody></table></div>
  <% if (slots.size() == 0) { %><p class="subtle">尚无号源。</p><% } %>
  <% } %>
</section>
<%@ include file="footer.jspf" %>
