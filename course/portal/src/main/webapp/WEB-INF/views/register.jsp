<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>患者注册 · 医疗云课程版</title>
  <link rel="stylesheet" href="<%= request.getContextPath() %>/assets/course.css">
  <link rel="stylesheet" href="<%= request.getContextPath() %>/assets/lab2-portal.css">
</head>
<body class="login-page">
<%@ include file="public-header.jspf" %>
<div class="auth-page-heading"><p>云服务门户 / 患者注册</p><h1>开启您的医疗云服务</h1><span>注册患者账号后，即可查询医院与医生号源。</span></div>
<div class="auth-shell">
  <%@ include file="auth-story.jspf" %>
  <main class="login-card">
    <p class="eyebrow">新用户登记</p>
    <h2>患者注册</h2>
    <p class="subtle">创建演示账号，体验云端诊疗流程。</p>
    <% if (request.getAttribute("error") != null) { %><p class="error" role="alert"><%= request.getAttribute("error") %></p><% } %>
    <form method="post" accept-charset="UTF-8" action="<%= request.getContextPath() %>/register">
      <input type="hidden" name="csrfToken" value="<%= request.getAttribute("csrfToken") %>">
      <label>账号<input name="username" autocomplete="username" pattern="[A-Za-z0-9_]{3,64}" minlength="3" maxlength="64" placeholder="3–64 位字母、数字或下划线" required></label>
      <label>密码<input name="password" type="password" autocomplete="new-password" placeholder="设置演示账号密码" required></label>
      <label>确认密码<input name="confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入密码" required></label>
      <button type="submit">注册患者账号</button>
    </form>
    <p class="auth-switch">已有账号？<a href="<%= request.getContextPath() %>/login">返回登录 →</a></p>
    <p class="note auth-safety">仅使用虚构演示信息；请勿输入真实患者信息。</p>
  </main>
</div>
<%@ include file="public-footer.jspf" %>
</body>
</html>
