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

class ClinicalSupplementJspTest {
    static Tomcat tomcat;static int port;
    static com.sun.net.httpserver.HttpServer rejectionServer;
    static final java.util.concurrent.atomic.AtomicInteger rejectionStatus=new java.util.concurrent.atomic.AtomicInteger(403), rejectionCalls=new java.util.concurrent.atomic.AtomicInteger();
    @BeforeAll static void start()throws Exception{
        tomcat=new Tomcat();tomcat.setBaseDir(Files.createTempDirectory("clinical-jsp-").toString());tomcat.setPort(0);Context c=tomcat.addWebapp("/portal",new File("src/main/webapp").getAbsolutePath());c.setParentClassLoader(ClinicalSupplementJspTest.class.getClassLoader());
        rejectionServer=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        rejectionServer.createContext("/api",x->{rejectionCalls.incrementAndGet();ClinicalSupplementPortalTest.read(x.getRequestBody());byte[] b="{}".getBytes("UTF-8");x.sendResponseHeaders(rejectionStatus.get(),b.length);x.getResponseBody().write(b);x.close();});rejectionServer.start();
        Tomcat.addServlet(c,"clinical-actions",new com.medcloud.course.portal.web.ClinicalSupplementServlet(new com.medcloud.course.portal.web.PaasClient("http://127.0.0.1:"+rejectionServer.getAddress().getPort()+"/api")));
        for(String path:new String[]{"/patient/complaint/save","/doctor/clinical/save","/doctor/clinical/complete","/doctor/correction/append"})c.addServletMappingDecoded(path,"clinical-actions");
        Tomcat.addServlet(c,"clinical-test-session",new HttpServlet(){protected void doGet(HttpServletRequest r,HttpServletResponse s){HttpSession session=r.getSession(true);session.setAttribute("role","patient".equals(r.getParameter("role"))?com.medcloud.course.contract.Role.PATIENT:com.medcloud.course.contract.Role.DOCTOR);session.setAttribute("csrfToken","csrf");session.setAttribute("accessToken","trusted");}});c.addServletMappingDecoded("/clinical-test-session","clinical-test-session");
        Tomcat.addServlet(c,"clinical-fixture",new HttpServlet(){protected void doGet(HttpServletRequest r,HttpServletResponse s)throws IOException,javax.servlet.ServletException{
            r.setAttribute("clinicalHome","1".equals(r.getParameter("patient"))?"/patient":"/doctor");r.setAttribute("targetId",ClinicalSupplementPortalTest.ID);r.setAttribute("csrfToken","csrf");r.setAttribute("expectedRevision","2");r.setAttribute("operationKey",ClinicalSupplementPortalTest.KEY);r.setAttribute("clinicalWritable",!"1".equals(r.getParameter("frozen")));r.setAttribute("complaint","<script>evil</script>😀");r.setAttribute("diagnosis","诊断");r.setAttribute("advice","建议");
            ObjectMapper m=new ObjectMapper();r.setAttribute("patientComplaint",m.readTree("{\"description\":\"患者自述\",\"revision\":3,\"updatedAt\":\"2026-10-16\"}"));r.setAttribute("clinicalWorkspace",m.readTree("{\"draft\":{\"content\":{\"notes\":\"private-draft-secret\"}}}"));
            r.setAttribute("recordDetails",m.readTree("{\"original\":{\"diagnosis\":\"原始诊断\"},\"effective\":{\"diagnosis\":\"有效诊断\"},\"currentRevision\":2}"));r.setAttribute("correctionPage",m.readTree("{\"items\":[{\"sequence\":2,\"before\":{\"diagnosis\":\"更正前\"},\"after\":{\"diagnosis\":\"更正后\"},\"reason\":\"复核原因\"}],\"page\":2,\"pageSize\":5,\"total\":11}"));
            String view=r.getParameter("view");if(view==null)view="clinical-workspace";if(!java.util.Arrays.asList("clinical-workspace","patient-complaint","clinical-record-details","clinical-correction","hospital","platform").contains(view)){s.sendError(400);return;}r.getRequestDispatcher("/WEB-INF/views/"+view+".jsp").forward(r,s);
        }});c.addServletMappingDecoded("/fixture","clinical-fixture");tomcat.getConnector();tomcat.start();port=tomcat.getConnector().getLocalPort();
    }
    @AfterAll static void stop()throws Exception{try{if(tomcat!=null){tomcat.stop();tomcat.destroy();}}finally{if(rejectionServer!=null)rejectionServer.stop(0);}}
    static String render(String q)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/portal/fixture"+q).openConnection();try{assertEquals(200,c.getResponseCode());return new String(ClinicalSupplementPortalTest.read(c.getInputStream()),"UTF-8");}finally{c.disconnect();}}
    @Test void unicodeAndHtmlEscaped()throws Exception{String h=render("");assertTrue(h.contains("&lt;script&gt;evil&lt;/script&gt;😀"));assertFalse(h.contains("<script>evil</script>"));assertTrue(h.contains("/doctor/clinical/save"));assertTrue(h.contains("/doctor/clinical/complete"));for(String field:new String[]{"complaint","diagnosis","advice","notes","csrfToken","appointmentId","expectedRevision"})assertTrue(h.contains("name=\""+field+"\""),field);assertTrue(h.contains("name=\"csrfToken\" value=\"csrf\""));assertTrue(h.contains("name=\"appointmentId\" value=\""+ClinicalSupplementPortalTest.ID+"\""));}
    @Test void patientDraftNeverRendered()throws Exception{for(String view:new String[]{"patient-complaint","clinical-record-details"})assertFalse(render("?patient=1&view="+view).contains("private-draft-secret"));}
    @Test void patientCopyExplicitNotAutomatic()throws Exception{String h=render("");assertTrue(h.contains("name=\"copyPatientComplaint\""));assertTrue(h.contains("明确复制"));assertFalse(h.contains(">患者自述</textarea>"));}
    @Test void frozenHospitalReadOnly()throws Exception{for(String view:new String[]{"clinical-workspace","clinical-correction","patient-complaint"}){String h=render("?frozen=1&view="+view);assertFalse(h.contains("method=\"post\""));}}
    @Test void originalEffectiveHistoryDistinct()throws Exception{String h=render("?view=clinical-record-details&patient=1");for(String t:new String[]{"原始记录","当前有效版本","更正历史","原始诊断","有效诊断","更正前","更正后","复核原因","page=1","page=3"})assertTrue(h.contains(t),t);}
    @Test void correctionRetryFormKeepsKeyAndReason()throws Exception{String h=render("?view=clinical-correction");assertTrue(h.contains(ClinicalSupplementPortalTest.KEY));assertTrue(h.contains("name=\"reason\""));assertTrue(h.contains("name=\"expectedRevision\" value=\"2\""));}
    @Test void auditLabelsCoverThreeNewEvents()throws Exception{for(String view:new String[]{"hospital","platform"}){String h=render("?view="+view);for(String label:new String[]{"患者病情补充","接诊草稿保存","病历更正追加"})assertTrue(h.contains(label));assertFalse(h.contains("private-draft-secret"));assertFalse(h.contains("/doctor/correction"));assertFalse(h.contains("/patient/record-details"));}}
    private static String rejectedSubmission(String path,int status)throws Exception{
        rejectionStatus.set(status);rejectionCalls.set(0);
        HttpURLConnection session=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/portal/clinical-test-session?role="+(path.startsWith("/patient/")?"patient":"doctor")).openConnection();String cookie;
        try{assertEquals(200,session.getResponseCode());cookie=session.getHeaderField("Set-Cookie").split(";",2)[0];}finally{session.disconnect();}
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/portal"+path).openConnection();c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Cookie",cookie);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
        StringBuilder body=new StringBuilder();String[][] fields={{"csrfToken","csrf"},{"appointmentId",ClinicalSupplementPortalTest.ID},{"recordId",ClinicalSupplementPortalTest.ID},{"expectedRevision","7"},{"operationKey",ClinicalSupplementPortalTest.KEY},{"description","自己的描述<script>自述</script>😀"},{"complaint","自己的主诉<script>主诉</script>😀"},{"diagnosis","自己的诊断"},{"advice","自己的建议\n保留换行"},{"notes","自己的备注"},{"reason","自己的更正原因<script>原因</script>"}};
        for(String[] field:fields){if(body.length()>0)body.append('&');body.append(field[0]).append('=').append(URLEncoder.encode(field[1],"UTF-8"));}
        try{try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}assertEquals(status==401?302:status,c.getResponseCode());assertEquals(1,rejectionCalls.get(),"failure redraw must not read backend resources");assertEquals("no-store",c.getHeaderField("Cache-Control"));if(status==401){assertTrue(c.getHeaderField("Location").endsWith("/portal/login"));return new String(ClinicalSupplementPortalTest.read(c.getInputStream()),"UTF-8");}assertNull(c.getHeaderField("Location"));return new String(ClinicalSupplementPortalTest.read(c.getErrorStream()),"UTF-8");}finally{c.disconnect();}
    }
    @Test void forbiddenCorrectionKeepsOwnInputKeyAndRevisionReadOnly()throws Exception{
        String h=rejectedSubmission("/doctor/correction/append",403);
        for(String text:new String[]{"自己的主诉&lt;script&gt;主诉&lt;/script&gt;😀","自己的诊断","自己的建议\n保留换行","自己的备注","自己的更正原因&lt;script&gt;原因&lt;/script&gt;",ClinicalSupplementPortalTest.KEY,"name=\"expectedRevision\" value=\"7\""})assertTrue(h.contains(text),text);
        assertTrue(h.contains("readonly"));assertFalse(h.contains("<form"));assertFalse(h.contains("type=\"submit\""));assertFalse(h.contains("<script>"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"/patient/complaint/save","/doctor/clinical/save","/doctor/clinical/complete"})
    void forbiddenComplaintAndDraftKeepOwnInputAndRevisionReadOnly(String path)throws Exception{
        String h=rejectedSubmission(path,403);assertTrue(h.contains("name=\"expectedRevision\" value=\"7\""),path);assertTrue(h.contains(path.startsWith("/patient/")?"自己的描述&lt;script&gt;自述&lt;/script&gt;😀":"自己的主诉&lt;script&gt;主诉&lt;/script&gt;😀"),path);if(path.startsWith("/doctor/"))for(String text:new String[]{"自己的诊断","自己的建议\n保留换行","自己的备注"})assertTrue(h.contains(text),text);assertTrue(h.contains("readonly"));assertFalse(h.contains("<form"));assertFalse(h.contains("type=\"submit\""));assertFalse(h.contains("<script>"));
    }
    @Test void conflictStillOffersEditableCorrectionWithSameRetryKey()throws Exception{
        String h=rejectedSubmission("/doctor/correction/append",409);assertTrue(h.contains("method=\"post\""));assertTrue(h.contains("<textarea name=\"reason\" required>自己的更正原因&lt;script&gt;原因&lt;/script&gt;</textarea>"));assertTrue(h.contains(ClinicalSupplementPortalTest.KEY));assertTrue(h.contains("name=\"expectedRevision\" value=\"7\""));assertTrue(h.contains("type=\"submit\""));
    }
}
