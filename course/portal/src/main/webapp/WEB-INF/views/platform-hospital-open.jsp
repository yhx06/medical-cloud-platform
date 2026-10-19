<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<% String workspace="platform", workspaceLabel="开通医院", workspaceDescription="创建医院和首位医院管理员。初始密码由平台设置，成功后请通过约定渠道交付。"; %>
<% Object administrationFormUsername=request.getAttribute("username"); request.setAttribute("username",session.getAttribute("username")); %>
<%@ include file="header.jspf" %>
<% request.setAttribute("username",administrationFormUsername); %>
<section class="card">
  <h2>开通医院</h2>
  <form method="post" action="<%= context %>/platform/hospital/open">
    <label>医院名称<input name="hospitalName" value="<%= WebEscaper.html(attribute(request,"hospitalName")) %>" maxlength="100" required></label>
    <label>首管理员用户名<input name="username" value="<%= WebEscaper.html(attribute(request,"username")) %>" pattern="[A-Za-z0-9_]{3,64}" maxlength="64" autocomplete="off" required></label>
    <label>初始密码<input type="password" name="initialPassword" value="" autocomplete="new-password" required aria-describedby="password-help"></label>
    <label>确认初始密码<input type="password" name="confirmInitialPassword" value="" autocomplete="new-password" required></label>
    <input type="hidden" name="operationKey" value="<%= WebEscaper.html(attribute(request,"operationKey")) %>">
    <input type="hidden" name="csrfToken" value="<%= WebEscaper.html(attribute(request,"csrfToken")) %>">
    <p id="password-help" class="note">用户名为 3–64 位字母、数字或下划线；密码为 8–72 个 UTF-8 字节，不去除空格。成功页不显示密码。</p>
    <p class="note">如果响应丢失，请保留此页的相同操作编号并输入原密码手动重试。不要更换操作编号或自动重复提交。</p>
    <button type="submit">开通医院及首管理员</button>
  </form>
  <a href="<%= context %>/platform">返回医院列表</a>
</section>
<%@ include file="footer.jspf" %>
