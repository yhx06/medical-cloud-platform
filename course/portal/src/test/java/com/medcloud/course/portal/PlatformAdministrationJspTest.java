package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.Role;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class PlatformAdministrationJspTest {
    static Tomcat tomcat;static int port;static PlatformAdministrationPortalTest.Fixture backend;
    @BeforeAll static void start()throws Exception{
        tomcat=new Tomcat();tomcat.setBaseDir(Files.createTempDirectory("onboarding-jsp-").toString());tomcat.setPort(0);
        Context context=tomcat.addWebapp("/portal",new File("src/main/webapp").getAbsolutePath());context.setParentClassLoader(PlatformAdministrationJspTest.class.getClassLoader());
        Tomcat.addServlet(context,"onboarding-fixture",new HttpServlet(){protected void doGet(HttpServletRequest r,HttpServletResponse s)throws IOException,javax.servlet.ServletException{
            r.setAttribute("portalTitle","平台管理员");r.setAttribute("csrfToken","csrf");r.setAttribute("hospitalName","<img src=x onerror=evil()>");r.setAttribute("username","<script>evil</script>");r.setAttribute("operationKey",PlatformAdministrationPortalTest.KEY);r.setAttribute("tenantId","legacy +/#");r.setAttribute("expectedStatus","ACTIVE");r.setAttribute("adminId","admin-a");r.setAttribute("status","DISABLED");
            ObjectMapper json=new ObjectMapper();r.setAttribute("administrators",json.readTree("{\"tenant\":{\"id\":\"legacy +/#\",\"name\":\"<script>evil</script>\",\"status\":\"DISABLED\"},\"items\":[{\"id\":\"admin-a\",\"tenantId\":\"legacy +/#\",\"username\":\"<script>evil</script>\",\"status\":\"ACTIVE\"}],\"page\":2,\"size\":5,\"total\":11,\"activeAdministratorCount\":1}"));
            String view=r.getParameter("view");if(!java.util.Arrays.asList("platform-hospital-open","platform-administrator-create","platform-administrators","platform-administration-result","platform").contains(view)){s.sendError(400);return;}
            r.getRequestDispatcher("/WEB-INF/views/"+view+".jsp").forward(r,s);
        }});context.addServletMappingDecoded("/onboarding-fixture","onboarding-fixture");
        Tomcat.addServlet(context,"onboarding-session",new HttpServlet(){protected void doGet(HttpServletRequest r,HttpServletResponse s){HttpSession session=r.getSession(true);session.setAttribute("role",Role.PLATFORM_ADMIN);session.setAttribute("username","PlatformOperator");session.setAttribute("accessToken","fake");session.setAttribute("csrfToken","csrf");}});context.addServletMappingDecoded("/onboarding-session","onboarding-session");
        tomcat.getConnector();tomcat.start();port=tomcat.getConnector().getLocalPort();
    }
    @AfterAll static void stop()throws Exception{try{if(tomcat!=null){tomcat.stop();tomcat.destroy();}}finally{if(backend!=null)backend.close();}}
    static String render(String view)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/portal/onboarding-fixture?view="+view).openConnection();try{assertEquals(200,c.getResponseCode());return new String(PortalFixture.read(c.getInputStream()),"UTF-8");}finally{c.disconnect();}}
    @Test void formsRenderWithoutJavascriptWithLabelsEscapingAndBlankPasswords()throws Exception{
        for(String view:new String[]{"platform-hospital-open","platform-administrator-create"}){String h=render(view);assertTrue(h.contains("method=\"post\""));assertTrue(h.contains("name=\"operationKey\" value=\""+PlatformAdministrationPortalTest.KEY+"\""));assertTrue(h.contains("&lt;script&gt;evil&lt;/script&gt;"));assertFalse(h.contains("<script>evil</script>"));for(String name:new String[]{"username","initialPassword","confirmInitialPassword"})assertTrue(h.matches("(?s).*<label[^>]*>.*name=\""+name+"\".*"),name);assertTrue(h.contains("name=\"initialPassword\" value=\"\""));assertTrue(h.contains("name=\"confirmInitialPassword\" value=\"\""));assertTrue(h.indexOf("name=\"username\"")<h.indexOf("name=\"initialPassword\""));assertTrue(h.indexOf("name=\"initialPassword\"")<h.indexOf("name=\"confirmInitialPassword\""));assertTrue(h.contains("原密码"));}
    }
    @Test void administratorsRenderEncodedPagingAndLastAdminHintForFrozenHospital()throws Exception{
        String h=render("platform-administrators");assertTrue(h.contains("tenantId=legacy+%2B%2F%23"));assertTrue(h.contains("page=1"));assertTrue(h.contains("page=3"));assertTrue(h.contains("最后一位"));assertTrue(h.contains("disabled"));assertTrue(h.contains("&lt;script&gt;evil&lt;/script&gt;"));assertFalse(h.contains("<script>evil</script>"));assertTrue(h.contains("/platform/hospital/administrator/create"));
    }
    @Test void resultUnknownShowsExpiredAndPlatformReturnWithoutPost()throws Exception{String h=render("platform-administration-result");assertTrue(h.contains("已失效"));assertTrue(h.contains("/platform"));assertFalse(h.contains("method=\"post\""));}
    @Test void actualFailedServletForwardDoesNotRenderOrRetainSubmittedPassword()throws Exception{
        backend=new PlatformAdministrationPortalTest.Fixture();backend.status=409;
        // This endpoint invokes the actual action servlet, then its actual JSP dispatcher.
        Context context=(Context)tomcat.getHost().findChild("/portal");Tomcat.addServlet(context,"onboarding-action-test",backend.servlet);context.addServletMappingDecoded("/platform/hospital/open","onboarding-action-test");
        HttpURLConnection login=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/portal/onboarding-session").openConnection();String cookie;try{assertEquals(200,login.getResponseCode());cookie=login.getHeaderField("Set-Cookie").split(";",2)[0];}finally{login.disconnect();}
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/portal/platform/hospital/open").openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Cookie",cookie);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
        String password=PlatformAdministrationPortalTest.PASSWORD;String body="csrfToken=csrf&hospitalName=Hospital&username=NewUser&operationKey="+PlatformAdministrationPortalTest.KEY+"&initialPassword="+URLEncoder.encode(password,"UTF-8")+"&confirmInitialPassword="+URLEncoder.encode(password,"UTF-8");
        try{try(OutputStream out=c.getOutputStream()){out.write(body.getBytes("UTF-8"));}assertEquals(409,c.getResponseCode());String h=new String(PortalFixture.read(c.getErrorStream()),"UTF-8");assertFalse(h.contains(password));assertTrue(h.contains(PlatformAdministrationPortalTest.KEY));assertTrue(h.contains("name=\"initialPassword\" value=\"\""));assertTrue(h.contains("name=\"username\" value=\"NewUser\""));assertTrue(h.contains("class=\"account-name\">PlatformOperator</span>"));assertEquals("no-store",c.getHeaderField("Cache-Control"));assertEquals(1,backend.writes);}finally{c.disconnect();}
    }
}
