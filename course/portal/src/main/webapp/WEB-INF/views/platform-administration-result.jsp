<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.medcloud.course.contract.HospitalAdminCreationView,com.medcloud.course.portal.web.PlatformAdministrationWorkspace" %>
<% String workspace="platform", workspaceLabel="开通结果", workspaceDescription="创建结果可刷新查看；医院与账号的当前状态请查阅列表。"; %>
<% Object administrationFormUsername=request.getAttribute("username"); request.setAttribute("username",session.getAttribute("username")); %>
<%@ include file="header.jspf" %>
<% request.setAttribute("username",administrationFormUsername); %>
<section class="card">
  <% HospitalAdminCreationView creation=(HospitalAdminCreationView)request.getAttribute("creationResult"); if(creation==null){ %>
  <h2>结果已失效或不可用</h2><p>此结果不属于当前会话，或已超过保存数量。请返回平台列表核对，不要重复创建。</p>
  <% } else { %>
  <h2>医院管理员创建成功</h2>
  <dl><dt>医院名称</dt><dd><%= WebEscaper.html(creation.getHospitalName()) %></dd><dt>医院编号</dt><dd><%= WebEscaper.html(creation.getTenantId()) %></dd>
    <dt>管理员用户名</dt><dd><%= WebEscaper.html(creation.getUsername()) %></dd><dt>管理员编号</dt><dd><%= WebEscaper.html(creation.getAdministratorId()) %></dd></dl>
  <p>请通过约定渠道交付已设置的初始密码。此页不保存或显示密码。</p>
  <a href="<%= context %>/platform/hospital/administrators?tenantId=<%= WebEscaper.html(PlatformAdministrationWorkspace.query(creation.getTenantId())) %>">查看医院管理员</a>
  <% } %>
  <a href="<%= context %>/platform">返回医院列表</a>
</section>
<%@ include file="footer.jspf" %>
