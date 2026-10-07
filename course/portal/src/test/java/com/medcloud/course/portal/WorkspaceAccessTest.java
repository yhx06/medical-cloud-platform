package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.*;
import com.medcloud.course.portal.factory.CloudServiceFactory;
import com.medcloud.course.portal.web.PaasClient;
import com.medcloud.course.portal.web.PortalServlet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAccessTest {
    @Test void lateUnauthorizedClearsAllPrivateDataAndFlash() throws Exception {
        for (String endpoint : new String[] {"/hospital/context", "/hospital/statistics"}) {
            try (PortalFixture f = new PortalFixture()) {
                f.status.put(endpoint, 401);
                MockHttpServletRequest request = f.request("GET", "/hospital", Role.HOSPITAL_ADMIN);
                MockHttpSession session = (MockHttpSession) request.getSession();
                session.setAttribute("flash", "科室已创建"); session.setAttribute("initialPassword", "one-time");
                MockHttpServletResponse response = new MockHttpServletResponse();
                f.servlet.service(request, response);
                for (String key : new String[] {"orders", "departments", "doctors", "slots", "statistics",
                        "hospitalContext", "notice", "initialPassword", "username", "csrfToken"}) {
                    assertNull(request.getAttribute(key), key);
                }
                assertTrue(session.isInvalid());
                assertEquals("/WEB-INF/views/login.jsp", response.getForwardedUrl());
                assertTrue(request.getAttribute("error").toString().contains("重新登录"));
                assertTrue(f.calls.contains("GET /hospital/orders"), "401 occurs after earlier private reads");
                if (endpoint.endsWith("context")) assertFalse(f.calls.contains("GET /hospital/statistics"));
            }
        }
    }

    @Test void unavailableContextDefaultsToReadonlyButKeepsHistory() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            f.status.put("/hospital/context", 503);
            MockHttpServletRequest request = f.request("GET", "/hospital", Role.HOSPITAL_ADMIN);
            f.servlet.service(request, new MockHttpServletResponse());
            assertEquals(Boolean.FALSE, request.getAttribute("businessWritable"));
            assertNotNull(request.getAttribute("contextError"));
            assertNotNull(request.getAttribute("orders"));
            assertNotNull(request.getAttribute("statistics"));
        }
    }

    @Test void doctorContextFailsClosedForUnknownOrDisabledState() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            for (String state : new String[] {"UNKNOWN", "DISABLED"}) {
                f.data.put("/doctor/context", "{\"id\":\"hospital-a\",\"name\":\"甲医院\",\"status\":\"" + state + "\",\"businessWritable\":true}");
                MockHttpServletRequest request = f.request("GET", "/doctor", Role.DOCTOR);
                f.servlet.service(request, new MockHttpServletResponse());
                assertEquals(Boolean.FALSE, request.getAttribute("businessWritable"));
                assertNotNull(request.getAttribute("appointments")); assertNotNull(request.getAttribute("records"));
            }
        }
    }

    @Test void resourceFailuresDoNotHideOtherHospitalOrDoctorSections() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            f.status.put("/hospital/departments", 500); f.status.put("/doctor/appointments", 500);
            MockHttpServletRequest hospital = f.request("GET", "/hospital", Role.HOSPITAL_ADMIN);
            f.servlet.service(hospital, new MockHttpServletResponse());
            assertNull(hospital.getAttribute("departments")); assertNotNull(hospital.getAttribute("departmentsError"));
            assertNotNull(hospital.getAttribute("doctors")); assertNotNull(hospital.getAttribute("slots"));
            assertNotNull(hospital.getAttribute("orders"));
            MockHttpServletRequest doctor = f.request("GET", "/doctor", Role.DOCTOR);
            f.servlet.service(doctor, new MockHttpServletResponse());
            assertNotNull(doctor.getAttribute("records")); assertNotNull(doctor.getAttribute("appointmentsError"));
        }
    }

    @Test void recordFailureDoesNotHidePatientBillAndUnauthorizedBillClearsRecord() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            String base = "/patient/appointments/" + PortalFixture.ID;
            f.status.put("/catalog/hospitals", 503); f.status.put(base + "/record", 500);
            MockHttpServletRequest request = f.request("GET", "/patient", Role.PATIENT);
            request.addParameter("appointmentId", PortalFixture.ID);
            f.servlet.service(request, new MockHttpServletResponse());
            assertNotNull(request.getAttribute("appointments")); assertNotNull(request.getAttribute("order"));
            f.status.remove(base + "/record"); f.status.put(base + "/order", 401);
            request = f.request("GET", "/patient", Role.PATIENT); request.addParameter("appointmentId", PortalFixture.ID);
            MockHttpSession session = (MockHttpSession) request.getSession();
            f.servlet.service(request, new MockHttpServletResponse());
            assertNull(request.getAttribute("record")); assertNull(request.getAttribute("appointments"));
            assertTrue(session.isInvalid());
        }
    }

    @Test void unauthorizedPostInvalidatesSessionAndRemovesSuccessNotice() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            f.status.put("/platform/tenants/" + PortalFixture.ID + "/status", 401);
            MockHttpServletRequest request = f.statusRequest();
            request.getSession().setAttribute("flash", "previous success");
            request.setAttribute("notice", "previous success"); request.setAttribute("initialPassword", "one-time");
            MockHttpSession session = (MockHttpSession) request.getSession();
            MockHttpServletResponse response = new MockHttpServletResponse();
            f.servlet.service(request, response);
            assertTrue(session.isInvalid()); assertNull(request.getAttribute("notice"));
            assertNull(request.getAttribute("initialPassword"));
            assertEquals("/WEB-INF/views/login.jsp", response.getForwardedUrl());
        }
    }

    @Test void nonJsonUnauthorizedResponseStillInvalidatesSession() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            f.status.put("/hospital/context", 401); f.rawError = "<html>unauthorized</html>";
            MockHttpServletRequest request = f.request("GET", "/hospital", Role.HOSPITAL_ADMIN);
            MockHttpSession session = (MockHttpSession) request.getSession();
            f.servlet.service(request, new MockHttpServletResponse());
            assertTrue(session.isInvalid()); assertNull(request.getAttribute("orders"));
        }
    }

    @Test void patientLateUnauthorizedPreservesPublicDirectoryOnly() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            String base = "/patient/appointments/" + PortalFixture.ID;
            f.data.put("/catalog/hospitals", "[{\"id\":\"hospital-a\",\"name\":\"甲医院\"}]");
            f.data.put("/catalog/departments", "[{\"id\":\"department-a\",\"name\":\"内科\"}]");
            f.status.put(base + "/order", 401);
            MockHttpServletRequest request = f.request("GET", "/patient", Role.PATIENT);
            request.addParameter("tenantId", "hospital-a"); request.addParameter("appointmentId", PortalFixture.ID);
            f.servlet.service(request, new MockHttpServletResponse());
            assertNotNull(request.getAttribute("hospitals")); assertNotNull(request.getAttribute("departments"));
            assertNotNull(request.getAttribute("doctors")); assertNotNull(request.getAttribute("slots"));
            assertNull(request.getAttribute("appointments")); assertNull(request.getAttribute("record"));
        }
    }
}

// The HTTP boundary is external; the servlet and real PaasClient remain under test.
final class PortalFixture implements AutoCloseable {
    static final String ID = "11111111-1111-1111-1111-111111111111";
    final Map<String, Integer> status = new HashMap<String, Integer>();
    final Map<String, String> data = new HashMap<String, String>();
    final List<String> calls = new ArrayList<String>();
    // Unfiltered ledger preserves every actual HTTP request, including independent audit directory reads.
    final List<String> rawReads = new ArrayList<String>();
    final List<String> auditDirectoryReads = new ArrayList<String>();
    private volatile boolean auditDirectoryPhase;
    final Map<String, String> queries = new HashMap<String, String>();
    final Map<String, String> bodies = new HashMap<String, String>();
    final Map<String, String> contentTypes = new HashMap<String, String>();
    String rawError;
    final HttpServer server;
    final PortalServlet servlet;
    static String contextJson() throws Exception {
        return new ObjectMapper().writeValueAsString(new TenantContextView(ID, "甲医院", "ACTIVE", true));
    }
    static String statisticsJson(String role) throws Exception {
        TenantView tenant = new TenantView(ID, "甲医院", "ACTIVE");
        StatisticsMetrics metrics = new StatisticsMetrics(1, 1, 0, 0, 0, 0, 0, 0, 0, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        HospitalStatisticsView hospital = new HospitalStatisticsView(tenant, metrics, LegacyUnknownTime.zero());
        return new ObjectMapper().writeValueAsString(new StatisticsReport(
                role.equals("hospital") ? "HOSPITAL" : "PLATFORM", "2030-05-01", "2030-05-01", "Asia/Shanghai", "2030-05-01T12:00:00+08:00",
                metrics, Collections.singletonList(new DailyStatistics("2030-05-01", metrics)), Collections.singletonList(hospital),
                LegacyUnknownTime.zero(), role.equals("platform") ? new TenantCounts(1, 1, 0) : null));
    }
    PortalFixture() throws Exception {
        ObjectMapper json = new ObjectMapper();
        TenantView tenant = new TenantView(ID, "甲医院", "ACTIVE");
        for (String role : new String[] {"hospital", "platform"}) {
            data.put("/" + role + "/statistics", statisticsJson(role));
        }
        data.put("/platform/tenants", json.writeValueAsString(new TenantPage(Collections.singletonList(tenant), 1, 1, 20)));
        data.put("/platform/tenants/" + ID + "/status", json.writeValueAsString(new TenantView(ID, "甲医院", "DISABLED")));
        data.put("/hospital/context", contextJson());
        data.put("/doctor/context", data.get("/hospital/context"));
        data.put("/hospital/orders", "[{\"id\":\"order-a\",\"status\":\"PAID\",\"amount\":39.00}]");
        data.put("/patient/appointments/" + ID + "/record", "{\"diagnosis\":\"虚构诊断\"}");
        data.put("/patient/appointments/" + ID + "/order", "{\"status\":\"UNPAID\",\"amount\":39.00,\"businessWritable\":false}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring(4);
            String rawQuery = exchange.getRequestURI().getRawQuery();
            rawReads.add(exchange.getRequestMethod() + " " + path + (rawQuery == null ? "" : "?" + rawQuery));
            String body = new String(read(exchange.getRequestBody()), StandardCharsets.UTF_8);
            // The controller loads management before audit, then walks the directory. Query size alone cannot distinguish them.
            if (auditDirectoryPhase && "/platform/tenants".equals(path)) {
                auditDirectoryReads.add(exchange.getRequestMethod() + " " + path + (rawQuery == null ? "" : "?" + rawQuery));
            } else {
                calls.add(exchange.getRequestMethod() + " " + path);
                queries.put(path, rawQuery);
                bodies.put(path, body);
                contentTypes.put(path, exchange.getRequestHeaders().getFirst("Content-Type"));
            }
            if ("/platform/audit-events".equals(path)) auditDirectoryPhase = true;
            int code = status.containsKey(path) ? status.get(path) : 200;
            String reply = code >= 400 ? (rawError == null ? "{\"code\":\"ERROR\",\"message\":\"SQL private detail\"}" : rawError)
                    : "{\"code\":\"OK\",\"data\":" + data.getOrDefault(path, "[]") + "}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        servlet = new PortalServlet(new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api"), new CloudServiceFactory());
    }
    static byte[] read(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n); return output.toByteArray();
    }
    MockHttpServletRequest request(String method, String path, Role role) {
        // Explicit workflow boundary, including repeated management size=100 requests after prior directory reads.
        auditDirectoryPhase = false;
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/portal" + path);
        request.setServletPath(path); request.setContextPath("/portal");
        MockHttpSession session = new MockHttpSession(); session.setAttribute("role", role);
        session.setAttribute("accessToken", "token"); session.setAttribute("csrfToken", "csrf"); request.setSession(session); return request;
    }
    MockHttpServletRequest statusRequest() {
        MockHttpServletRequest request = request("POST", "/platform/tenant/status", Role.PLATFORM_ADMIN);
        request.addParameter("id", ID); request.addParameter("status", "DISABLED"); request.addParameter("expectedStatus", "ACTIVE");
        request.addParameter("csrfToken", "csrf"); return request;
    }
    public void close() { server.stop(0); }
}
