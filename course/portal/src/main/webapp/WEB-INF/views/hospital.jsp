<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode" %>
<% String workspace = "hospital", workspaceLabel = "医院管理", workspaceDescription = "维护科室与医生，发布诊疗号源，查询本院模拟账单。"; %>
<%@ include file="header.jspf" %>
<% JsonNode departments = (JsonNode) request.getAttribute("departments");
   JsonNode doctors = (JsonNode) request.getAttribute("doctors");
   JsonNode slots = (JsonNode) request.getAttribute("slots");
   String csrf = WebEscaper.html(String.valueOf(request.getAttribute("csrfToken"))); %>
<section class="card" id="departments">
  <div class="section-heading"><div><p class="section-kicker">01 / DEPARTMENTS</p><h2>科室管理</h2></div></div>
  <p class="subtle">停用有未来有效号源或未完成预约的科室会被拒绝。</p>
  <form method="post" action="<%= context %>/hospital/department/create" class="inline-form">
    <input type="hidden" name="csrfToken" value="<%= csrf %>">
    <label>新科室名称<input name="name" maxlength="100" required></label>
    <button type="submit">新增科室</button>
  </form>
  <% if (departments != null && departments.isArray()) { %>
  <div class="table-wrap" tabindex="0" role="region" aria-label="科室列表，左右滚动查看更多"><table><thead><tr><th>科室编号</th><th>名称与状态</th><th>操作</th></tr></thead><tbody>
    <% for (JsonNode department : departments) {
         String id = WebEscaper.html(department.path("id").asText());
         String status = department.path("status").asText(); %>
    <tr><td class="identifier"><%= id %></td><td><strong><%= WebEscaper.html(department.path("name").asText()) %></strong><br><%= stateBadge(status) %></td>
    <td><details class="edit-panel"><summary>编辑科室</summary><form method="post" action="<%= context %>/hospital/department/edit" class="clinical-form">
      <input type="hidden" name="csrfToken" value="<%= csrf %>">
      <input type="hidden" name="id" value="<%= id %>">
      <label>名称<input name="name" maxlength="100" value="<%= WebEscaper.html(department.path("name").asText()) %>" required></label>
      <label>状态<select name="status"><option value="ACTIVE" <%= "ACTIVE".equals(status) ? "selected" : "" %>>启用</option><option value="DISABLED" <%= "DISABLED".equals(status) ? "selected" : "" %>>停用</option></select></label>
      <button type="submit">保存科室</button>
    </form></details></td></tr>
    <% } %>
  </tbody></table></div>
  <% if (departments.size() == 0) { %><p class="empty-state">尚无科室，请先创建科室再添加医生。</p><% } %>
  <% } %>
</section>
<section class="card" id="doctors">
  <div class="section-heading"><div><p class="section-kicker">02 / DOCTORS</p><h2>医生管理</h2></div></div>
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
  <div class="table-wrap" tabindex="0" role="region" aria-label="医生列表，左右滚动查看更多"><table><thead><tr><th>账号</th><th>显示名与科室</th><th>操作</th></tr></thead><tbody>
    <% for (JsonNode doctor : doctors) {
         String id = WebEscaper.html(doctor.path("userId").asText());
         String status = doctor.path("status").asText();
         String selectedDepartment = doctor.path("departmentId").asText(); %>
    <tr><td><strong><%= WebEscaper.html(doctor.path("username").asText()) %></strong><br><span class="identifier subtle"><%= id %></span></td>
    <td><%= WebEscaper.html(doctor.path("displayName").asText()) %><br><%= stateBadge(status) %></td>
    <td><details class="edit-panel"><summary>编辑医生</summary><form method="post" action="<%= context %>/hospital/doctor/edit" class="clinical-form">
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
    </form></details></td></tr>
    <% } %>
  </tbody></table></div>
  <% if (doctors.size() == 0) { %><p class="empty-state">尚无医生，可为本院科室创建演示医生账号。</p><% } %>
  <% } %>
</section>
<section class="card" id="schedule">
  <div class="section-heading"><div><p class="section-kicker">03 / SCHEDULE</p><h2>排班号源</h2></div></div>
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
  <div class="table-wrap" tabindex="0" role="region" aria-label="排班号源列表，左右滚动查看更多"><table><thead><tr><th>号源编号</th><th>排班与状态</th><th>编辑</th></tr></thead><tbody>
    <% for (JsonNode slot : slots) {
         String slotId = WebEscaper.html(slot.path("id").asText());
         String slotStatus = slot.path("status").asText();
         String slotDepartment = slot.path("departmentId").asText();
         String slotDoctor = slot.path("doctorId").asText(); %>
    <tr><td class="identifier"><%= slotId %></td><td><strong><%= WebEscaper.html(slot.path("date").asText()) %></strong><br>
      <%= WebEscaper.html(slot.path("startTime").asText()) %>–<%= WebEscaper.html(slot.path("endTime").asText()) %><br>
      <%= stateBadge(slotStatus) %></td>
    <td><details class="edit-panel"><summary>编辑号源</summary><form method="post" action="<%= context %>/hospital/slot/edit" class="clinical-form">
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
    </form></details></td></tr>
    <% } %>
  </tbody></table></div>
  <% if (slots.size() == 0) { %><p class="empty-state">尚无号源，发布未来排班后患者即可查询。</p><% } %>
  <% } %>
</section>
<section class="card" id="orders">
  <div class="section-heading"><div><p class="section-kicker">04 / BILLING</p><h2>诊疗账单</h2></div><span class="section-tag">本院账单</span></div>
  <form method="get" action="<%= context %>/hospital#orders" class="inline-form">
    <label>缴费状态<select name="status"><option value="">全部</option><option value="PAID" <%= "PAID".equals(request.getAttribute("status")) ? "selected" : "" %>>已缴费</option><option value="UNPAID" <%= "UNPAID".equals(request.getAttribute("status")) ? "selected" : "" %>>未缴费</option></select></label>
    <label>开始日期<input type="date" name="from" value="<%= request.getAttribute("from") == null ? "" : request.getAttribute("from") %>"></label>
    <label>结束日期<input type="date" name="to" value="<%= request.getAttribute("to") == null ? "" : request.getAttribute("to") %>"></label>
    <button type="submit">查询账单</button>
    <a class="reset-link" href="<%= context %>/hospital#orders">重置筛选</a>
  </form>
  <p class="subtle">日期按北京时间的缴费日筛选；未缴费账单没有缴费日期。</p>
  <% JsonNode orders = (JsonNode) request.getAttribute("orders");
     if (orders != null && orders.isArray()) { %>
  <div class="table-wrap" tabindex="0" role="region" aria-label="诊疗账单列表，左右滚动查看更多"><table><thead><tr><th>账单编号</th><th>预约编号</th><th>金额</th><th>状态</th><th>缴费时间</th></tr></thead><tbody>
    <% for (JsonNode order : orders) { %>
    <tr><td class="identifier"><%= WebEscaper.html(order.path("id").asText()) %></td>
      <td class="identifier"><%= WebEscaper.html(order.path("appointmentId").asText()) %></td>
      <td class="money">¥<%= WebEscaper.html(order.path("amount").asText()) %></td>
      <td><%= stateBadge(order.path("status").asText()) %></td>
      <td><%= WebEscaper.html(order.path("paidAt").isNull() && "PAID".equals(order.path("status").asText())
              ? "历史时间未知" : order.path("paidAt").asText("—")) %></td></tr>
    <% } %>
  </tbody></table></div>
  <% if (orders.size() == 0) { %><p class="empty-state">暂无符合条件的账单。</p><% } %>
  <% } %>
</section>
<%@ include file="footer.jspf" %>
