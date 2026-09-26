<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ include file="header.jspf" %>
<section class="card">
  <h2>平台架构视图</h2>
  <div class="layers">
    <div><strong>SaaS</strong><span>Tomcat 9 · 医院、医生、患者、平台门户</span></div>
    <div><strong>PaaS</strong><span>WildFly 10 · JAX-RS 接口与 EJB 业务组件</span></div>
    <div><strong>IaaS</strong><span>MySQL · JNDI 数据源与 JPA 持久化</span></div>
  </div>
  <p class="subtle">本页不展示患者明细。跨机构统计和监管脱敏汇总属于后续扩展。</p>
</section>
<%@ include file="footer.jspf" %>
