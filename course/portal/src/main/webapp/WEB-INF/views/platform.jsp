<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode" %>
<% String workspace = "platform", workspaceLabel = "平台概览", workspaceDescription = "管理医院业务状态，查看跨院脱敏汇总与课程平台架构。"; %>
<%@ include file="header.jspf" %>
<section class="card" id="tenant-management">
  <div class="section-heading"><div><p class="section-kicker">01 / HOSPITALS</p><h2>医院管理</h2></div></div>
  <p class="subtle">停用医院将暂停预约、取消、接诊、缴费和资源维护，历史可查。医院账号与资源原有状态保留，重新启用后仍按原业务规则办理。</p>
  <%= sectionError(request, "tenantListError") %>
  <% JsonNode tenants = (JsonNode) request.getAttribute("tenants");
     if (tenants != null && tenants.path("items").isArray()) {
       long tenantPage = Math.max(1, tenants.path("page").asLong(1));
       long tenantSize = Math.max(1, Math.min(100, tenants.path("size").asLong(20)));
       long tenantTotal = Math.max(0, tenants.path("total").asLong());
       long tenantPages = tenantTotal / tenantSize + (tenantTotal % tenantSize == 0 ? 0 : 1); %>
  <div class="table-wrap" tabindex="0" role="region" aria-label="医院管理列表，左右滚动查看更多"><table><thead><tr><th>医院编号</th><th>医院名称</th><th>业务状态</th><th>启停操作</th></tr></thead><tbody>
    <% for (JsonNode tenant : tenants.path("items")) {
         String tenantStatus = tenant.path("status").asText();
         String tenantName = WebEscaper.html(tenant.path("name").asText());
         boolean knownTenantStatus = "ACTIVE".equals(tenantStatus) || "DISABLED".equals(tenantStatus); %>
    <tr><td class="identifier"><%= WebEscaper.html(tenant.path("id").asText()) %></td><td><strong><%= tenantName %></strong></td><td><%= stateBadge(tenantStatus) %></td>
      <td><% if (knownTenantStatus) { String targetStatus = "ACTIVE".equals(tenantStatus) ? "DISABLED" : "ACTIVE"; %>
        <form method="post" action="<%= context %>/platform/tenant/status" class="tenant-status-form">
          <input type="hidden" name="id" value="<%= WebEscaper.html(tenant.path("id").asText()) %>">
          <input type="hidden" name="status" value="<%= targetStatus %>">
          <input type="hidden" name="expectedStatus" value="<%= tenantStatus %>">
          <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request, "csrfToken")) %>">
          <input type="hidden" name="page" value="<%= tenantPage %>">
          <input type="hidden" name="size" value="<%= tenantSize %>">
          <%= hiddenFilters(statsQuery) %>
          <p class="note">目标：<%= tenantName %> · <%= "DISABLED".equals(targetStatus) ? "停用" : "启用" %>。<%= "DISABLED".equals(targetStatus) ? "暂停预约、取消、接诊、缴费和资源维护，历史可查" : "启用后仍按原状态与截止规则办理，不自动恢复已停用的资源" %>。</p>
          <button type="submit" class="<%= "DISABLED".equals(targetStatus) ? "button--danger" : "button--secondary" %>"><%= "DISABLED".equals(targetStatus) ? "停用医院" : "启用医院" %></button>
        </form>
        <% } else { %><span class="read-only-reason">状态未知，请刷新确认</span><% } %>
      </td></tr>
    <% } %>
  </tbody></table></div>
  <% if (tenants.path("items").size() == 0) { %><p class="empty-state">当前页暂无医院。</p><% } %>
  <nav class="pagination" aria-label="医院列表分页"><span>共 <%= tenantTotal %> 家医院 · 第 <%= tenantPage %> 页</span>
    <% if (tenantPage > 1) { %><a href="<%= filteredUrl(context + "/platform", "page=" + (tenantPage - 1) + "&size=" + tenantSize + (statsQuery.isEmpty() ? "" : "&" + statsQuery), "tenant-management") %>">上一页</a><% } %>
    <% if (tenantPage < tenantPages && tenantPage < Integer.MAX_VALUE) { %><a href="<%= filteredUrl(context + "/platform", "page=" + (tenantPage + 1) + "&size=" + tenantSize + (statsQuery.isEmpty() ? "" : "&" + statsQuery), "tenant-management") %>">下一页</a><% } %>
  </nav>
  <% } %>
</section>
<%@ include file="statistics.jspf" %>
<section class="card" id="architecture">
  <div class="section-heading"><div><p class="section-kicker">04 / ARCHITECTURE</p><h2>平台架构视图</h2></div></div>
  <div class="layers">
    <div><strong>SaaS</strong><span>Tomcat 9 · 医院、医生、患者、平台门户</span></div>
    <div><strong>PaaS</strong><span>WildFly 10 · JAX-RS 接口与 EJB 业务组件；MySQL 数据服务 · JNDI/JPA</span></div>
    <div><strong>IaaS</strong><span>计算、网络、存储与虚拟化 · 70 台逻辑服务器模型（不实际启动）</span></div>
  </div>
  <p class="subtle">统计仅展示跨院脱敏数值和医院汇总，不提供患者身份、临床正文或明细下钻。</p>
</section>
<%@ include file="footer.jspf" %>
