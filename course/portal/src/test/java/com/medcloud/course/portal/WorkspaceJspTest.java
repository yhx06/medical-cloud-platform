package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.portal.web.PortalServlet;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Real Jasper rendering: catches broken section links, untranslated states and lost form contracts. */
class WorkspaceJspTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Tomcat tomcat;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("course-workspace-jsp-").toString());
        tomcat.setPort(0);
        Context context = tomcat.addWebapp("/portal", new File("src/main/webapp").getAbsolutePath());
        context.setParentClassLoader(WorkspaceJspTest.class.getClassLoader());
        Tomcat.addServlet(context, "portal-routing", new PortalServlet());
        // Apply after Tomcat's inherited web.xml so the real '/' controller mapping is not overwritten.
        context.addLifecycleListener(event -> {
            if ("configure_start".equals(event.getType())) {
                for (String route : PortalServlet.class.getAnnotation(WebServlet.class).urlPatterns())
                    context.addServletMappingDecoded(route, "portal-routing");
            }
        });
        Tomcat.addServlet(context, "workspace-fixture", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
                    throws ServletException, java.io.IOException {
                String page = request.getPathInfo().substring(1);
                request.setAttribute("portalTitle", "测试工作台");
                request.setAttribute("username", "<script>account</script>");
                request.setAttribute("csrfToken", "fixture-csrf");
                if ("patient".equals(page)) {
                    request.setAttribute("tenantId", "tenant-id"); request.setAttribute("departmentId", "department-id");
                    request.setAttribute("doctorId", "doctor-id"); request.setAttribute("date", "2030-01-01");
                    request.setAttribute("patientFilters", "tenantId=tenant-id&departmentId=department-id&doctorId=doctor-id&date=2030-01-01");
                    request.setAttribute("hospitals", JSON.readTree("[{\"id\":\"tenant-id\",\"name\":\"演示医院 <script>unsafe</script>\"}]"));
                    request.setAttribute("departments", JSON.readTree("[{\"id\":\"department-id\",\"name\":\"演示内科\"}]"));
                    request.setAttribute("doctors", JSON.readTree("[{\"id\":\"doctor-id\",\"name\":\"张医生\"}]"));
                }
                if ("patient".equals(page) || "doctor".equals(page)) {
                    request.setAttribute("appointments", JSON.readTree("["
                            + "{\"id\":\"booked-id\",\"status\":\"BOOKED\",\"patientId\":\"patient-id\",\"slotId\":\"slot-id\"},"
                            + "{\"id\":\"progress-id\",\"status\":\"IN_PROGRESS\"},"
                            + "{\"id\":\"done-id\",\"status\":\"COMPLETED\"},"
                            + "{\"id\":\"cancel-id\",\"status\":\"CANCELED\"}]"));
                    request.setAttribute("slots", JSON.readTree("[{\"id\":\"bookable-slot\",\"date\":\"2030-01-01\",\"startTime\":\"09:00\",\"endTime\":\"10:00\",\"departmentId\":\"department-id\",\"doctorId\":\"doctor-id\",\"remaining\":1,\"capacity\":2,\"fee\":39}]"));
                    request.setAttribute("records", JSON.readTree("[{\"appointmentId\":\"12345678-1234-1234-1234-123456789012\",\"patientId\":\"patient-id\",\"completedAt\":\"2030-01-01 10:00:00\",\"complaint\":\"演示主诉 <script>unsafe</script>\",\"diagnosis\":\"虚构诊断\",\"advice\":\"课堂演示建议\",\"notes\":\"虚构备注\"}]"));
                    request.setAttribute("order", JSON.readTree("{\"id\":\"order-id\",\"amount\":39,\"status\":\"UNPAID\"}"));
                }
                if ("hospital".equals(page)) {
                    request.setAttribute("departments", JSON.readTree("[{\"id\":\"department-id\",\"name\":\"内科\",\"status\":\"ACTIVE\"}]"));
                    request.setAttribute("doctors", JSON.readTree("[{\"userId\":\"doctor-id\",\"username\":\"doctor_a\",\"displayName\":\"演示医生\",\"departmentId\":\"department-id\",\"status\":\"DISABLED\"}]"));
                    request.setAttribute("slots", JSON.readTree("[{\"id\":\"slot-id\",\"departmentId\":\"department-id\",\"doctorId\":\"doctor-id\",\"date\":\"2030-01-01\",\"startTime\":\"09:00\",\"endTime\":\"10:00\",\"capacity\":2,\"fee\":39,\"status\":\"WITHDRAWN\"}]"));
                    request.setAttribute("orders", JSON.readTree("[{\"id\":\"order-id\",\"appointmentId\":\"done-id\",\"amount\":39,\"status\":\"PAID\",\"paidAt\":null}]"));
                }
                request.getRequestDispatcher("/WEB-INF/views/" + page + ".jsp").forward(request, response);
            }
        });
        context.addServletMappingDecoded("/ui/*", "workspace-fixture");
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception {
        if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
    }

    @Test
    void patientSelectsDirectoryNamesInsteadOfTypingIdentifiers() throws Exception {
        String html = render("patient");
        assertSelectedOption(html, "tenantId", "tenant-id", "演示医院 &lt;script&gt;unsafe&lt;/script&gt;");
        assertSelectedOption(html, "departmentId", "department-id", "演示内科");
        assertSelectedOption(html, "doctorId", "doctor-id", "张医生");
        Matcher table = Pattern.compile("<section[^>]*id=\"slot-search\"[^>]*>([\\s\\S]*?)</section>").matcher(html);
        assertTrue(table.find());
        assertTrue(table.group(1).contains("<td>演示内科</td>"));
        assertTrue(table.group(1).contains("<td>张医生</td>"));
        assertFalse(html.contains("<script>unsafe</script>"));
        assertTrue(html.contains("name=\"refreshDirectory\""), "No-JS users need a way to update the dependent options");
        assertTrue(html.contains("href=\"/portal/patient#slot-search\""));
    }

    @Test
    void onlyCompletedAppointmentsLinkToBillingAndPreserveCurrentFilters() throws Exception {
        String html = render("patient");
        Matcher links = Pattern.compile("<a[^>]*href=\"([^\"]+appointmentId[^\"]+)\"[^>]*>查看病历与账单</a>").matcher(html);
        assertTrue(links.find(), "Completed history rows need a direct entry");
        assertEquals("/portal/patient?tenantId=tenant-id&amp;departmentId=department-id&amp;doctorId=doctor-id&amp;date=2030-01-01&amp;appointmentId=done-id#record-billing", links.group(1));
        assertFalse(links.find(), "Booked/in-progress/canceled rows must not offer a missing record");
        Matcher section = Pattern.compile("<section[^>]*id=\"record-billing\"[^>]*>([\\s\\S]*?)</section>").matcher(html);
        assertTrue(section.find());
        for (String[] field : new String[][] {{"tenantId", "tenant-id"}, {"departmentId", "department-id"}, {"doctorId", "doctor-id"}, {"date", "2030-01-01"}})
            assertTrue(section.group(1).contains("type=\"hidden\" name=\"" + field[0] + "\" value=\"" + field[1] + "\""));
    }

    private static void assertSelectedOption(String html, String field, String value, String label) {
        Matcher select = Pattern.compile("<select[^>]*name=\"" + field + "\"[^>]*>([\\s\\S]*?)</select>").matcher(html);
        assertTrue(select.find(), "Missing directory select " + field);
        assertTrue(select.group(1).contains("<option value=\"" + value + "\" selected>" + label + "</option>"),
                "Selected directory identity/name not retained for " + field);
    }

    @Test
    void workspacesServeExternalEnhancementScriptAndDateFiltersHaveResetLinks() throws Exception {
        String html = render("patient");
        Matcher script = Pattern.compile("<script[^>]*src=\"([^\"]+portal\\.js)\"[^>]*>").matcher(html);
        assertTrue(script.find(), "Workspace must load navigation/cascade enhancement");
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + script.group(1)).openConnection();
        try {
            assertEquals(200, connection.getResponseCode());
            assertTrue(connection.getContentType().contains("javascript"));
        } finally { connection.disconnect(); }
        assertTrue(render("doctor").contains("href=\"/portal/doctor#clinical-history\""));
        assertTrue(render("hospital").contains("href=\"/portal/hospital#orders\""));
    }

    @Test
    void dataTablesHaveNamedKeyboardFocusableScrollRegions() throws Exception {
        for (String page : new String[]{"patient", "doctor", "hospital"}) {
            String html = render(page);
            Matcher regions = Pattern.compile("<div[^>]*class=\"table-wrap\"[^>]*>").matcher(html);
            int count = 0;
            while (regions.find()) {
                String region = regions.group();
                assertTrue(region.contains("tabindex=\"0\""), page + " scroll region is not keyboard reachable");
                assertTrue(region.contains("role=\"region\""), page + " scroll region has no semantic role");
                assertTrue(Pattern.compile("aria-label=\"[^\"]+\"").matcher(region).find());
                count++;
            }
            assertTrue(count > 0, page + " must render its data tables");
        }
    }

    @Test
    void linkedStylesheetIsPublicCssInsteadOfBeingInterceptedByPortalRouting() throws Exception {
        String html = render("login");
        Matcher stylesheet = Pattern.compile("href=\"([^\"]+\\.css)\"").matcher(html);
        assertTrue(stylesheet.find(), "Auth page must link its stylesheet");
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port
                + stylesheet.group(1)).openConnection();
        try {
            assertEquals(200, connection.getResponseCode(), "Anonymous visitors need the linked stylesheet");
            assertTrue(connection.getContentType().startsWith("text/css"));
        } finally { connection.disconnect(); }
    }

    @Test
    void loginExplainsDemoScopeWithoutLosingLoginOrRegistration() throws Exception {
        String html = render("login");
        assertTrue(html.contains("aria-label=\"医疗云介绍\""));
        assertTrue(html.contains("不接入真实医疗或支付服务"));
        assertTrue(html.contains("action=\"/portal/login\""));
        assertTrue(html.contains("href=\"/portal/register\""));
        assertTrue(html.contains("autocomplete=\"current-password\""));
    }

    @Test
    void registrationKeepsCsrfAndPasswordConfirmationInSharedAuthLayout() throws Exception {
        String html = render("register");
        assertTrue(html.contains("aria-label=\"医疗云介绍\""));
        assertTrue(html.contains("action=\"/portal/register\""));
        assertTrue(html.contains("name=\"csrfToken\" value=\"fixture-csrf\""));
        assertTrue(html.contains("name=\"confirmPassword\""));
        assertTrue(html.contains("href=\"/portal/login\""));
    }

    @Test
    void patientHasWorkingSectionNavigationAndReadableStates() throws Exception {
        String html = render("patient");
        assertSectionLinks(html, "预约挂号", "我的预约", "病历与账单");
        assertStates(html, "已预约", "接诊中", "已完成", "已取消", "待缴费");
        assertTrue(html.contains("action=\"/portal/patient/cancel\""));
        assertTrue(html.contains("name=\"appointmentId\" value=\"booked-id\""));
        assertTrue(html.contains("name=\"csrfToken\" value=\"fixture-csrf\""));
        assertTrue(html.contains("action=\"/portal/patient/pay\""));
        assertFormField(html, "/portal/patient/book", "slotId", "bookable-slot");
        assertFormField(html, "/portal/patient/book", "csrfToken", "fixture-csrf");
        assertFalse(html.contains("<script>account</script>"));
        assertTrue(html.contains("&lt;script&gt;account&lt;/script&gt;"));
    }

    @Test
    void doctorNavigationKeepsClinicalInputsAndLocalizesAppointmentStates() throws Exception {
        String html = render("doctor");
        assertSectionLinks(html, "接诊工作台", "病历记录");
        assertStates(html, "已预约", "接诊中", "已完成", "已取消");
        assertTrue(html.contains("action=\"/portal/doctor/start\""));
        assertTrue(html.contains("action=\"/portal/doctor/complete\""));
        for (String field : new String[]{"complaint", "diagnosis", "advice", "notes", "csrfToken"})
            assertTrue(html.contains("name=\"" + field + "\""), field);
        assertTrue(html.contains("12345678-1234-1234-1234-123456789012"));
        assertTrue(html.contains("演示主诉 &lt;script&gt;unsafe&lt;/script&gt;"));
        assertFalse(html.contains("<script>unsafe</script>"));
        assertFormField(html, "/portal/doctor/complete", "appointmentId", "progress-id");
        assertFormField(html, "/portal/doctor/complete", "csrfToken", "fixture-csrf");
    }

    @Test
    void hospitalNavigationKeepsManagementActionsAndLocalizesStates() throws Exception {
        String html = render("hospital");
        assertSectionLinks(html, "科室管理", "医生管理", "排班号源", "诊疗账单");
        assertStates(html, "已启用", "已停用", "已撤回", "已缴费");
        for (String action : new String[]{"department/create", "department/edit", "doctor/create",
                "doctor/edit", "slot/create", "slot/edit"})
            assertTrue(html.contains("action=\"/portal/hospital/" + action + "\""), action);
        assertTrue(html.contains("历史时间未知"));
    }

    @Test
    void platformViewSeparatesInfrastructureFromDatabasePlatformService() throws Exception {
        String html = render("platform");
        assertSectionLinks(html, "架构概览");
        assertTrue(html.contains("计算、网络、存储与虚拟化"));
        assertTrue(html.contains("MySQL 数据服务"));
    }

    private static void assertStates(String html, String... labels) {
        for (String label : labels)
            assertTrue(html.contains(">" + label + "</span>"), "Missing state " + label);
    }

    private static void assertFormField(String html, String action, String field, String value) {
        Matcher form = Pattern.compile("<form[^>]*action=\"" + Pattern.quote(action)
                + "\"[^>]*>([\\s\\S]*?)</form>").matcher(html);
        assertTrue(form.find(), "Missing form " + action);
        assertTrue(form.group(1).contains("name=\"" + field + "\" value=\"" + value + "\""),
                action + " lost " + field);
    }

    private static void assertSectionLinks(String html, String... labels) {
        assertTrue(html.contains("aria-label=\"工作台导航\""));
        Matcher links = Pattern.compile("href=\"#([^\"]+)\"").matcher(html);
        int count = 0;
        while (links.find()) {
            assertTrue(html.contains("id=\"" + links.group(1) + "\""), "Dead anchor " + links.group(1));
            count++;
        }
        assertTrue(count >= labels.length + 1, "Sections and skip link must be navigable");
        for (String label : labels) assertTrue(html.contains(label));
    }

    private static String render(String page) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port
                + "/portal/ui/" + page).openConnection();
        try {
            assertEquals(200, connection.getResponseCode(), page + " JSP must compile");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
