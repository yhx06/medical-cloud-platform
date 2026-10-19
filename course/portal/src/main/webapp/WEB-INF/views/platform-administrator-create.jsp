<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.medcloud.course.portal.web.PlatformAdministrationWorkspace" %>
<% String workspace="platform", workspaceLabel="增补医院管理员", workspaceDescription="为已有医院增补启用的管理员账号；此操作不会改变医院业务状态。"; %>
<% Object administrationFormUsername=request.getAttribute("username"); request.setAttribute("username",session.getAttribute("username")); %>
<%@ include file="header.jspf" %>
<% request.setAttribute("username",administrationFormUsername); %>
<section class="card">
  <h2>增补医院管理员</h2>
  <p>医院编号：<%= WebEscaper.html(attribute(request,"tenantId")) %></p>
  <form method="post" action="<%= context %>/platform/hospital/administrator/create">
    <input type="hidden" name="tenantId" value="<%= WebEscaper.html(attribute(request,"tenantId")) %>">
    <label>管理员用户名<input name="username" value="<%= WebEscaper.html(attribute(request,"username")) %>" pattern="[A-Za-z0-9_]{3,64}" maxlength="64" autocomplete="off" required></label>
    <label>初始密码<input type="password" name="initialPassword" value="" autocomplete="new-password" required aria-describedby="password-help"></label>
    <label>确认初始密码<input type="password" name="confirmInitialPassword" value="" autocomplete="new-password" required></label>
    <input type="hidden" name="operationKey" value="<%= WebEscaper.html(attribute(request,"operationKey")) %>">
    <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request,"csrfToken")) %>">
    <p id="password-help" class="note">密码为 8–72 个 UTF-8 字节，不去除空格；确认值仅用于本次页面校验，成功页不显示密码。</p>
    <p class="note">响应丢失后，请保留相同操作编号并输入原密码手动重试。不要更换编号或自动重复提交。</p>
    <button type="submit">增补管理员</button>
  </form>
  <a href="<%= context %>/platform/hospital/administrators?tenantId=<%= WebEscaper.html(PlatformAdministrationWorkspace.query(attribute(request,"tenantId"))) %>">返回管理员列表</a>
  <a href="<%= context %>/platform">返回医院列表</a>
</section>
<%@ include file="footer.jspf" %>
