<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<% String workspace = "platform", workspaceLabel = "平台概览", workspaceDescription = "查看课程平台的服务分层与技术实现，不展示患者明细。"; %>
<%@ include file="header.jspf" %>
<section class="card" id="architecture">
  <div class="section-heading"><div><p class="section-kicker">01 / ARCHITECTURE</p><h2>平台架构视图</h2></div></div>
  <div class="layers">
    <div><strong>SaaS</strong><span>Tomcat 9 · 医院、医生、患者、平台门户</span></div>
    <div><strong>PaaS</strong><span>WildFly 10 · JAX-RS 接口与 EJB 业务组件；MySQL 数据服务 · JNDI/JPA</span></div>
    <div><strong>IaaS</strong><span>计算、网络、存储与虚拟化 · 70 台逻辑服务器模型（不实际启动）</span></div>
  </div>
  <p class="subtle">本页不展示患者明细。跨机构统计和监管脱敏汇总属于后续扩展。</p>
</section>
<%@ include file="footer.jspf" %>
