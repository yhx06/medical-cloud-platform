<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.medcloud.course.portal.web.WebEscaper" %>
<% response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer"); %>
<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="referrer" content="no-referrer"><title>本次下载授权</title><link rel="stylesheet" href="<%= request.getContextPath() %>/assets/course.css"></head>
<body><main class="page-shell"><section class="card"><h1>本次下载授权</h1>
<p>链接自签发起最多有效 300 秒（5 分钟），到期时间：<%= WebEscaper.html(String.valueOf(request.getAttribute("downloadExpiresAt"))) %>。</p>
<p>此链接由持有者使用，请勿转发。账号停用或移出资料不会立即撤销已签发的链接；链接到期或存储实际清理后失效。此授权不表示浏览器已完成下载。</p>
<a href="<%= WebEscaper.html(String.valueOf(request.getAttribute("downloadUrl"))) %>" rel="noreferrer" referrerpolicy="no-referrer">下载附件</a>
<p><a href="<%= request.getContextPath() %><%= WebEscaper.html(String.valueOf(request.getAttribute("attachmentHome"))) %>">返回工作台</a></p>
</section></main></body></html>
