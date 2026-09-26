<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.medcloud.course.portal.web.WebEscaper" %>
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>登录 · 医疗云课程版</title>
  <link rel="stylesheet" href="<%= request.getContextPath() %>/assets/course.css">
</head>
<body class="login-page">
  <main class="login-card">
    <p class="eyebrow">云服务工程 · SaaS / PaaS / IaaS</p>
    <h1>医疗云课程版</h1>
    <p class="subtle">预约、接诊与模拟缴费均通过 WildFly 业务服务完成。</p>
    <% if (request.getAttribute("error") != null) { %><p class="error"><%= request.getAttribute("error") %></p><% } %>
    <% if (request.getAttribute("notice") != null) { %><p class="notice"><%= request.getAttribute("notice") %></p><% } %>
    <form method="post" action="<%= request.getContextPath() %>/login">
      <label>账号<input name="username" autocomplete="username" required></label>
      <label>密码<input name="password" type="password" autocomplete="current-password" required></label>
      <button type="submit">登录系统</button>
    </form>
    <p><a href="<%= request.getContextPath() %>/register">患者注册</a></p>
    <p class="note">仅使用演示账号；请勿输入真实患者信息。</p>
  </main>
</body>
</html>
