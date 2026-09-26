<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>患者注册 · 医疗云课程版</title>
  <link rel="stylesheet" href="<%= request.getContextPath() %>/assets/course.css">
</head>
<body class="login-page">
  <main class="login-card">
    <p class="eyebrow">云服务工程 · SaaS / PaaS / IaaS</p>
    <h1>患者注册</h1>
    <% if (request.getAttribute("error") != null) { %><p class="error"><%= request.getAttribute("error") %></p><% } %>
    <form method="post" accept-charset="UTF-8" action="<%= request.getContextPath() %>/register">
      <input type="hidden" name="csrfToken" value="<%= request.getAttribute("csrfToken") %>">
      <label>账号<input name="username" autocomplete="username" pattern="[A-Za-z0-9_]{3,64}" minlength="3" maxlength="64" required></label>
      <label>密码<input name="password" type="password" autocomplete="new-password" required></label>
      <label>确认密码<input name="confirmPassword" type="password" autocomplete="new-password" required></label>
      <button type="submit">注册患者账号</button>
    </form>
    <p><a href="<%= request.getContextPath() %>/login">返回登录</a></p>
    <p class="note">仅使用虚构演示信息；请勿输入真实患者信息。</p>
  </main>
</body>
</html>
