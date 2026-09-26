<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.databind.JsonNode" %>
<%@ include file="header.jspf" %>
<section class="card">
  <h2>医院号源概览</h2>
  <p class="subtle">本课程切片展示医院号源目录；号源发布接口由后续医院管理模块补充。</p>
  <form method="get" action="<%= context %>/hospital" class="inline-form">
    <label>医院编号<input name="tenantId" value="<%= request.getAttribute("tenantId") == null ? "" : request.getAttribute("tenantId") %>" required></label>
    <button type="submit">查询</button>
  </form>
  <% JsonNode slots = (JsonNode) request.getAttribute("slots");
     if (slots != null && slots.isArray()) { %>
  <div class="table-wrap"><table><thead><tr><th>号源</th><th>日期</th><th>医生</th><th>容量</th><th>诊费</th></tr></thead><tbody>
  <% for (JsonNode slot : slots) { %>
    <tr><td><%= WebEscaper.html(slot.path("id").asText()) %></td>
    <td><%= WebEscaper.html(slot.path("date").asText()) %></td>
    <td><%= WebEscaper.html(slot.path("doctorId").asText()) %></td>
    <td><%= WebEscaper.html(slot.path("capacity").asText()) %></td>
    <td>¥<%= WebEscaper.html(slot.path("fee").asText()) %></td></tr>
  <% } %>
  </tbody></table></div>
  <% } %>
</section>
<%@ include file="footer.jspf" %>
