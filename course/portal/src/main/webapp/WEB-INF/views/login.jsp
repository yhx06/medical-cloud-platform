<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.medcloud.course.portal.web.WebEscaper" %>
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>登录 · 医疗云课程版</title>
  <link rel="stylesheet" href="<%= request.getContextPath() %>/assets/course.css">
  <link rel="stylesheet" href="<%= request.getContextPath() %>/assets/lab2-portal.css">
</head>
<body class="login-page">
<%@ include file="public-header.jspf" %>
<div class="auth-page-heading"><p>云服务门户 / 统一身份入口</p><h1>欢迎使用智慧医疗云</h1><span>从预约挂号到诊疗记录，在同一个门户有序办理。</span></div>
<div class="auth-shell">
  <%@ include file="auth-story.jspf" %>
  <main class="login-card">
    <p class="eyebrow">统一身份认证</p>
    <h2>登录医疗云</h2>
    <p class="subtle">使用您的演示账号，进入专属服务工作台。</p>
    <div class="auth-roles"><span>患者服务</span><span>医生接诊</span><span>医院管理</span><span>平台运营</span></div>
    <% if (request.getAttribute("error") != null) { %><p class="error" role="alert"><%= request.getAttribute("error") %></p><% } %>
    <% if (request.getAttribute("notice") != null) { %><p class="notice" role="status"><%= request.getAttribute("notice") %></p><% } %>
    <form method="post" action="<%= request.getContextPath() %>/login">
      <label>账号<input name="username" autocomplete="username" placeholder="请输入账号" required></label>
      <label>密码<input name="password" type="password" autocomplete="current-password" placeholder="请输入密码" required></label>
      <button type="submit">登录系统</button>
    </form>
    <p class="auth-switch">还没有患者账号？<a href="<%= request.getContextPath() %>/register">患者注册 →</a></p>
    <p class="note auth-safety">请勿输入真实患者信息或用于其他平台的密码。</p>
  </main>
</div>
<%@ include file="public-footer.jspf" %>
</body>
</html>
