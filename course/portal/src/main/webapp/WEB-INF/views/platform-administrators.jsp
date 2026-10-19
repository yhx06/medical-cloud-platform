<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode,com.medcloud.course.portal.web.PlatformAdministrationWorkspace" %>
<% String workspace="platform", workspaceLabel="医院管理员", workspaceDescription="维护医院管理员账号；冻结医院同样保护最后一位启用管理员，启用账号不会解冻医院。"; %>
<% Object administrationFormUsername=request.getAttribute("username"); request.setAttribute("username",session.getAttribute("username")); %>
<%@ include file="header.jspf" %>
<% request.setAttribute("username",administrationFormUsername); %>
<section class="card">
  <h2>医院管理员</h2>
  <% JsonNode administrators=(JsonNode)request.getAttribute("administrators"); if(administrators!=null){
       String managedTenant=administrators.path("tenant").path("id").asText();
       String tenantQuery="tenantId="+PlatformAdministrationWorkspace.query(managedTenant);
       long number=Math.max(1,administrators.path("page").asLong(1)), size=Math.max(1,Math.min(100,administrators.path("size").asLong(20))), total=Math.max(0,administrators.path("total").asLong()), active=administrators.path("activeAdministratorCount").asLong();
  %>
  <p><strong><%= WebEscaper.html(administrators.path("tenant").path("name").asText()) %></strong> · <%= WebEscaper.html(managedTenant) %> · <%= stateBadge(administrators.path("tenant").path("status").asText()) %></p>
  <a class="button" href="<%= context %>/platform/hospital/administrator/create?<%= WebEscaper.html(tenantQuery) %>">增补管理员</a>
  <p class="note">启用管理员共 <%= active %> 位。最后一位启用管理员不能停用；人数仅为页面提示，服务端会独立核实。</p>
  <div class="table-wrap" tabindex="0" role="region" aria-label="医院管理员列表"><table><thead><tr><th scope="col">管理员编号</th><th scope="col">用户名</th><th scope="col">账号状态</th><th scope="col">操作</th></tr></thead><tbody>
  <% for(JsonNode administrator:administrators.path("items")){String current=administrator.path("status").asText();boolean enabled="ACTIVE".equals(current), known=enabled || "DISABLED".equals(current); %>
  <tr><td><%= WebEscaper.html(administrator.path("id").asText()) %></td><td><%= WebEscaper.html(administrator.path("username").asText()) %></td><td><%= stateBadge(current) %></td><td>
    <% if(known){ %>
    <form method="post" action="<%= context %>/platform/hospital/administrator/status">
      <input type="hidden" name="tenantId" value="<%= WebEscaper.html(managedTenant) %>">
      <input type="hidden" name="adminId" value="<%= WebEscaper.html(administrator.path("id").asText()) %>">
      <input type="hidden" name="status" value="<%= enabled?"DISABLED":"ACTIVE" %>">
      <input type="hidden" name="expectedStatus" value="<%= current %>">
      <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request,"csrfToken")) %>">
      <button type="submit" <%= enabled && active<=1?"disabled aria-disabled=\"true\"":"" %>><%= enabled?"停用管理员":"启用管理员" %></button>
    </form>
    <% }else{ %>状态未知，请刷新复核<% } %>
  </td></tr><% } %>
  </tbody></table></div>
  <% if(administrators.path("items").size()==0){ %><p>当前页暂无管理员。</p><% } %>
  <nav class="pagination" aria-label="管理员分页"><span>共 <%= total %> 位 · 第 <%= number %> 页</span>
    <% if(number>1){ %><a href="<%= context %>/platform/hospital/administrators?<%= WebEscaper.html(tenantQuery) %>&amp;page=<%= number-1 %>&amp;size=<%= size %>">上一页</a><% } %>
    <% if(number<Integer.MAX_VALUE && number*size<total && number*size<=Integer.MAX_VALUE){ %><a href="<%= context %>/platform/hospital/administrators?<%= WebEscaper.html(tenantQuery) %>&amp;page=<%= number+1 %>&amp;size=<%= size %>">下一页</a><% } %>
  </nav>
  <% }else if(!attribute(request,"adminId").isEmpty()){ %>
  <p class="note">提交未完成。已保留原目标和期望状态；请另开管理员列表复核后再决定是否按原状态重试，不会自动覆盖。</p>
  <p>医院：<%= WebEscaper.html(attribute(request,"tenantId")) %> · 管理员：<%= WebEscaper.html(attribute(request,"adminId")) %> · 原期望状态：<%= WebEscaper.html(attribute(request,"expectedStatus")) %></p>
  <a href="<%= context %>/platform/hospital/administrators?tenantId=<%= WebEscaper.html(PlatformAdministrationWorkspace.query(attribute(request,"tenantId"))) %>" target="_blank" rel="noopener">另开列表复核</a>
  <% } %>
  <a href="<%= context %>/platform">返回医院列表</a>
</section>
<%@ include file="footer.jspf" %>
