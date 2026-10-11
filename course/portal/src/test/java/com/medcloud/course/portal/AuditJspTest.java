package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.Role;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real controller, HTTP PaaS boundary and Jasper rendering; no browser layout claim. */
class AuditJspTest {
    static Tomcat tomcat; static AuditPortalTest.Fixture fixture; static int port;
    @BeforeAll static void start() throws Exception {
        fixture = new AuditPortalTest.Fixture(); tomcat = new Tomcat(); tomcat.setBaseDir(Files.createTempDirectory("course-audit-jsp-").toString()); tomcat.setPort(0);
        Context c = tomcat.addWebapp("/portal",new File("src/main/webapp").getAbsolutePath()); c.setParentClassLoader(AuditJspTest.class.getClassLoader());
        Tomcat.addServlet(c,"authenticated-audit",new HttpServlet() {
            protected void doGet(HttpServletRequest r,HttpServletResponse s) throws ServletException,IOException {
                HttpSession session = r.getSession(true); session.setAttribute("role",r.getServletPath().equals("/hospital") ? Role.HOSPITAL_ADMIN : Role.PLATFORM_ADMIN);
                session.setAttribute("accessToken","fictional-test-token"); session.setAttribute("csrfToken","fictional-csrf"); session.setAttribute("username","虚构账号"); fixture.servlet.service(r,s);
            }
        });
        c.addLifecycleListener(e -> { if("configure_start".equals(e.getType())) {c.addServletMappingDecoded("/hospital","authenticated-audit");c.addServletMappingDecoded("/platform","authenticated-audit");} });
        tomcat.start(); port=tomcat.getConnector().getLocalPort();
    }
    @AfterAll static void stop() throws Exception { if(tomcat!=null){tomcat.stop();tomcat.destroy();} if(fixture!=null)fixture.close(); }
    @BeforeEach void reset() throws Exception {fixture.auditStatus=200; fixture.directoryStatus=200; fixture.delay=0; fixture.auditData=new ObjectMapper().readTree(AuditPortalTest.page(false));}

    @Test void auditUnavailableWordingIsRenderedLocallyFor500AndTimeout() throws Exception {
        for(String role:new String[]{"hospital","platform"}) {
            for(boolean timeout:new boolean[]{false,true}) {
                fixture.auditStatus=timeout?200:500; fixture.delay=timeout?8500:0;
                try {
                    String html=render(role), audit=section(html);
                    assertTrue(audit.contains("审计服务暂时不可用，请稍后重试"),role+" timeout="+timeout);
                    assertFalse(audit.contains("诊疗服务暂时不可用")); assertFalse(audit.contains("暂无审计事件")); assertFalse(audit.contains("共 0 条"));
                    assertTrue(html.contains(role.equals("hospital")?"fictional-order":"tenant-management"));
                } finally {fixture.delay=0;}
            }
        }
        fixture.auditStatus=500;
        com.medcloud.course.portal.web.PaasRejectedException generic=assertThrows(com.medcloud.course.portal.web.PaasRejectedException.class,
                () -> fixture.client.get("/hospital/audit-events","fictional-test-token"));
        assertEquals("诊疗服务暂时不可用，请稍后重试",generic.getMessage(),"Generic client wording remains available to its other consumers");
        for(int status:new int[]{400,403}) {
            fixture.auditStatus=status;
            String audit=section(render("hospital"));
            assertTrue(audit.contains(status==400?"提交的信息不完整或格式不正确":"当前账号没有执行该操作的权限"));
            assertFalse(audit.contains("审计服务暂时不可用"));
        }
    }
    @Test void bothAuditWorkspacesExplainHistoricalCoverageAndNoChangeAccessResults() throws Exception {
        for(String role:new String[]{"hospital","platform"}) {
            String audit=section(render(role));
            assertTrue(audit.contains("仅记录审计功能启用后的操作"),role);
            assertTrue(audit.contains("NO_CHANGE（未变更）是访问结果，不代表新的业务操作"),role);
        }
    }

    @Test void escapedLabelsAndLongRequestIdsFitScrollableRegion() throws Exception {
        String html=render("platform?auditTenantId="+AuditPortalTest.B); String audit=section(html);
        assertTrue(html.contains("href=\"#audit-events\"")); assertTrue(audit.contains("&lt;script&gt;unsafe&lt;/script&gt;"));
        assertTrue(audit.contains("&lt;img src=x&gt;")); assertFalse(audit.contains("<script>unsafe")); assertFalse(audit.contains("<img src=x>"));
        assertTrue(audit.contains("患者（已脱敏）")); assertTrue(audit.contains("不提供患者身份、临床正文或明细入口"));
        assertTrue(audit.contains("Asia/Shanghai")); assertTrue(audit.contains("失败")); assertTrue(audit.contains("未认证"));
        assertTrue(audit.contains("class=\"table-wrap audit-table\" tabindex=\"0\" role=\"region\" aria-label=\"审计事件"));
        assertTrue(audit.contains("<th scope=\"col\">请求编号</th>")); assertTrue(audit.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        for(String forbidden:new String[]{"actorId","objectId","actor_id","object_id","appointmentId","/patient","/doctor","clinical"})assertFalse(audit.contains(forbidden),forbidden);
        assertTrue(audit.contains("<option value=\""+AuditPortalTest.B+"\" selected>院外页 &lt;script&gt;unsafe&lt;/script&gt;</option>"));
        HttpURLConnection css=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/portal/assets/course.css").openConnection();
        try{assertEquals(200,css.getResponseCode());String body=read(css.getInputStream());assertTrue(body.contains(".audit-table"));assertTrue(body.contains("overflow-wrap: anywhere"));}finally{css.disconnect();}
    }
    @Test void emptyVersusUnavailableAreDistinct() throws Exception {
        fixture.auditData=new ObjectMapper().readTree(AuditPortalTest.page(true)); String empty=section(render("hospital"));
        assertTrue(empty.contains("当前筛选范围内暂无审计事件")); assertTrue(empty.contains("共 0 条"));
        fixture.auditStatus=500; String unavailable=section(render("hospital")); assertTrue(unavailable.contains("role=\"alert\""));
        assertFalse(unavailable.contains("共 0 条"));assertFalse(unavailable.contains("暂无审计事件"));
        assertTrue(render("hospital").contains("fictional-order"));
        fixture.auditStatus=200; fixture.auditData=new ObjectMapper().readTree("{}"); unavailable=section(render("hospital"));
        assertTrue(unavailable.contains("role=\"alert\""));assertFalse(unavailable.contains("共 0 条"));
    }
    @Test void ordinaryGetFormsAndPaginationPreserveFiltersWithoutScripts() throws Exception {
        String query="auditFrom=2030-05-01&auditTo=2030-05-02&auditEventType=LOGIN&auditResult=FAILED&auditPage=2&auditSize=1";
        String html=render("hospital?"+query+"&from=2030-05-21&to=2030-05-22&status=PAID&statsFrom=2030-05-03&statsTo=2030-05-04");
        String audit=section(html); assertTrue(audit.contains("<form method=\"get\" action=\"/portal/hospital#audit-events\""));
        for(String field:new String[]{"auditFrom","auditTo","auditEventType","auditResult","auditSize","statsFrom","statsTo","from","to","status"}) assertTrue(audit.contains("name=\""+field+"\""),field);
        assertFalse(audit.contains("name=\"auditTenantId\"")); assertFalse(audit.contains("name=\"auditPage\""),"New filter resets audit page");
        Matcher links=Pattern.compile("href=\"([^\"]+)\"[^>]*>(上一页|下一页)</a>").matcher(audit);int count=0;
        while(links.find()){String href=links.group(1).replace("&amp;","&");assertTrue(href.contains("auditEventType=LOGIN"));assertTrue(href.contains("auditResult=FAILED"));assertTrue(href.contains("statsFrom=2030-05-03"));assertTrue(href.contains("status=PAID"));String next=section(render(href.substring("/portal/".length()).split("#")[0]));assertTrue(next.contains("请求编号"));count++;}assertEquals(2,count);
        Matcher reset=Pattern.compile("href=\"([^\"]+)\"[^>]*>重置审计</a>").matcher(audit);assertTrue(reset.find());String resetUrl=reset.group(1).replace("&amp;","&");assertFalse(resetUrl.contains("auditFrom="));assertTrue(resetUrl.contains("statsFrom="));assertTrue(resetUrl.contains("status=PAID"));
        assertNotNull(section(render(resetUrl.substring("/portal/".length()).split("#")[0])));
        String platform=section(render("platform?"+query+"&auditTenantId="+AuditPortalTest.B+"&page=3&size=20"));
        assertTrue(platform.contains("name=\"page\" value=\"3\""));assertTrue(platform.contains("name=\"size\" value=\"20\""));assertTrue(platform.contains("auditTenantId="+AuditPortalTest.B));
        String invalid=section(render("hospital?auditFrom=%3Cscript%3Ebad%3C%2Fscript%3E&auditTo=2030-05-02"));assertTrue(invalid.contains("value=\"&lt;script&gt;bad&lt;/script&gt;\""));assertFalse(invalid.contains("&amp;lt;"));
    }
    @Test void statisticsAndManagementFormsRetainIndependentAuditSelection() throws Exception {
        String html=render("platform?auditFrom=2030-05-01&auditTo=2030-05-02&auditEventType=LOGIN&auditResult=FAILED&auditPage=2&auditSize=1&auditTenantId="+AuditPortalTest.B+"&page=3&size=20");
        Matcher form=Pattern.compile("<form method=\"get\"[^>]*#platform-statistics[^>]*>([\\s\\S]*?)</form>").matcher(html);assertTrue(form.find());
        for(String field:new String[]{"auditFrom","auditTo","auditEventType","auditResult","auditPage","auditSize","auditTenantId"})assertTrue(form.group(1).contains("name=\""+field+"\""),field);
        Matcher reset=Pattern.compile("href=\"([^\"]+)\"[^>]*>重置统计</a>").matcher(html);assertTrue(reset.find());assertTrue(reset.group(1).contains("auditTenantId="+AuditPortalTest.B));assertFalse(reset.group(1).contains("statsFrom="));
        Matcher status=Pattern.compile("<form method=\"post\"[^>]*tenant/status[^>]*>([\\s\\S]*?)</form>").matcher(html);assertTrue(status.find());assertTrue(status.group(1).contains("name=\"auditTenantId\" value=\""+AuditPortalTest.B+"\""));
        Matcher paging=Pattern.compile("href=\"([^\"]+#tenant-management)\"").matcher(html);assertTrue(paging.find());assertTrue(paging.group(1).contains("auditTenantId="+AuditPortalTest.B));
        String hospital=render("hospital?auditFrom=2030-05-01&auditTo=2030-05-02&auditResult=FAILED");
        Matcher orders=Pattern.compile("<form method=\"get\"[^>]*#orders[^>]*>([\\s\\S]*?)</form>").matcher(hospital);assertTrue(orders.find());assertTrue(orders.group(1).contains("name=\"auditResult\" value=\"FAILED\""));
    }
    static String section(String html){Matcher m=Pattern.compile("<section[^>]*id=\"audit-events\"[^>]*>([\\s\\S]*?)</section>").matcher(html);assertTrue(m.find(),"Rendered audit region absent");return m.group(1);}
    static String render(String path)throws Exception {HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/portal/"+path).openConnection();try{assertEquals(200,c.getResponseCode(),"JSP/controller HTTP "+path);return read(c.getInputStream());}finally{c.disconnect();}}
    static String read(InputStream input)throws IOException{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
}
