package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles and renders the actual JSP, without treating this as browser acceptance. */
class AttachmentJspTest {
    static Tomcat tomcat;static int port;
    @BeforeAll static void start() throws Exception {
        tomcat=new Tomcat();tomcat.setBaseDir(Files.createTempDirectory("attachment-jsp-").toString());tomcat.setPort(0);
        Context c=tomcat.addWebapp("/portal",new File("src/main/webapp").getAbsolutePath());c.setParentClassLoader(AttachmentJspTest.class.getClassLoader());
        Tomcat.addServlet(c,"attachments-fixture",new HttpServlet(){protected void doGet(HttpServletRequest r,HttpServletResponse s)throws IOException,javax.servlet.ServletException{
            r.setAttribute("csrfToken","fixture-csrf");r.setAttribute("attachmentHome","/patient");r.setAttribute("appointmentId",AttachmentMultipartTest.ID);
            r.setAttribute("targetId",AttachmentMultipartTest.ID);r.setAttribute("uploadKey",AttachmentMultipartTest.ID);r.setAttribute("attachmentWritable",!"1".equals(r.getParameter("frozen")));
            r.setAttribute("attachmentDeleteAllowed",Boolean.TRUE);r.setAttribute("attachmentMaxBytes",10485760L);
            r.setAttribute("attachmentPage",new ObjectMapper().readTree("{\"items\":[{\"fileId\":\""+AttachmentMultipartTest.ID+"\",\"sourceType\":\"PATIENT_APPOINTMENT\",\"fileName\":\"<script>bad</script>.pdf\",\"sizeBytes\":3,\"contentType\":\"application/pdf\"}],\"total\":21,\"page\":2,\"pageSize\":20}"));
            if("1".equals(r.getParameter("empty")))r.setAttribute("attachmentPage",new ObjectMapper().readTree("{\"items\":[],\"total\":1,\"page\":2,\"pageSize\":20}"));
            r.setAttribute("downloadUrl","https://files.example.test/object?signature=fake&x=\"unsafe\"");r.setAttribute("downloadExpiresAt","2030-01-01T00:05:00Z");
            String view="1".equals(r.getParameter("download"))?"attachment-download":"attachments";
            if("hospital".equals(r.getParameter("admin"))||"platform".equals(r.getParameter("admin"))){view=r.getParameter("admin");r.setAttribute("auditPage",new ObjectMapper().readTree("{\"items\":[{\"eventType\":\"FILE_UPLOAD\",\"objectType\":\"FILE_METADATA\",\"fileName\":\"should-never-show.pdf\",\"url\":\"signed-secret\"}],\"total\":1,\"page\":1,\"size\":20}"));}
            r.getRequestDispatcher("/WEB-INF/views/"+view+".jsp").forward(r,s);
        }});c.addServletMappingDecoded("/fixture","attachments-fixture");tomcat.getConnector();tomcat.start();port=tomcat.getConnector().getLocalPort();
    }
    @AfterAll static void stop()throws Exception{if(tomcat!=null){tomcat.stop();tomcat.destroy();}}
    static String render(String query)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/portal/fixture"+query).openConnection();try{assertEquals(200,c.getResponseCode());return new String(AttachmentClientTest.read(c.getInputStream()),"UTF-8");}finally{c.disconnect();}}
    @Test void fileNameAndUrlAttributeEscaped()throws Exception {
        String html=render("");assertTrue(html.contains("&lt;script&gt;bad&lt;/script&gt;.pdf"));assertFalse(html.contains("<script>bad</script>"));
        String download=render("?download=1");assertTrue(download.contains("signature=fake&amp;x=&quot;unsafe&quot;"));assertTrue(download.contains("rel=\"noreferrer\""));assertTrue(download.contains("300"));assertFalse(download.contains("prefetch"));
    }
    @Test void noJsFormsAndFrozenHistoryAndEmptyPageNavigation()throws Exception {
        String html=render("");assertTrue(html.contains("enctype=\"multipart/form-data\""));assertTrue(html.contains("/patient/files/upload\""));assertTrue(html.contains("/patient/files/delete\""));assertTrue(html.contains("/files/download\""));assertTrue(html.contains("10 MB（10,485,760 字节）"));
        String frozen=render("?frozen=1");assertFalse(frozen.contains("/patient/files/upload\""));assertTrue(frozen.contains("/files/download\""));
        String empty=render("?empty=1");assertTrue(empty.contains("上一页"));assertTrue(empty.contains("暂无"));
    }
    @Test void administratorsSeeAttachmentAuditLabelsWithoutContentLinks()throws Exception {
        for(String role:new String[]{"hospital","platform"}){String html=render("?admin="+role);for(String label:new String[]{"附件上传","附件移出资料","附件下载授权","附件元数据"})assertTrue(html.contains(label),role+label);assertFalse(html.contains("should-never-show.pdf"));assertFalse(html.contains("signed-secret"));assertFalse(html.contains("/files/download"));assertFalse(html.contains("/patient/files"));assertFalse(html.contains("/doctor/files"));}
    }
}
