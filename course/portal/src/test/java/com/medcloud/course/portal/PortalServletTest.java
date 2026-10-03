package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.Role;
import com.medcloud.course.portal.factory.CloudServiceFactory;
import com.medcloud.course.portal.web.PaasClient;
import com.medcloud.course.portal.web.PortalServlet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PortalServletTest {
    @Test
    void protectedPlatformChildPathDeniesPatientBeforePaasAccess() throws Exception {
        try (PortalFixture fixture = new PortalFixture()) {
            MockHttpServletRequest request = fixture.request("GET", "/platform/tenant/status", Role.PATIENT);
            MockHttpServletResponse response = new MockHttpServletResponse();
            fixture.servlet.service(request, response);
            assertEquals(403, response.getStatus());
            assertEquals(0, fixture.calls.size());
        }
    }
    @Test
    void successfulLoginCreatesRoleSessionAndRedirectsToPatientPortal() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", exchange -> {
            byte[] response = "{\"code\":\"OK\",\"message\":\"ok\",\"data\":{\"accessToken\":\"signed-token\",\"username\":\"patient_a\",\"role\":\"PATIENT\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            PortalServlet servlet = new PortalServlet(
                    new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api"),
                    new CloudServiceFactory());
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/portal/login");
            request.setContextPath("/portal");
            request.setServletPath("/login");
            request.addParameter("username", "patient_a");
            request.addParameter("password", "Demo@123456");
            MockHttpServletResponse response = new MockHttpServletResponse();

            servlet.service(request, response);

            assertEquals("/portal/patient", response.getRedirectedUrl());
            assertEquals("signed-token", request.getSession().getAttribute("accessToken"));
            assertEquals(Role.PATIENT, request.getSession().getAttribute("role"));
            assertNotNull(request.getSession().getAttribute("csrfToken"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void patientCannotOpenDoctorPortal() throws Exception {
        PortalServlet servlet = new PortalServlet(new PaasClient("http://127.0.0.1:1/api"),
                new CloudServiceFactory());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/portal/doctor");
        request.setContextPath("/portal");
        request.setServletPath("/doctor");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", Role.PATIENT);
        session.setAttribute("accessToken", "signed-token");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertEquals(403, response.getStatus());
    }

    @Test
    void actionPathCannotBeRewrittenBySubmittedAppointmentId() throws Exception {
        PortalServlet servlet = new PortalServlet(new PaasClient("http://127.0.0.1:1/api"),
                new CloudServiceFactory());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/portal/doctor/start");
        request.setContextPath("/portal");
        request.setServletPath("/doctor/start");
        request.addParameter("appointmentId", "../../auth/login");
        request.addParameter("csrfToken", "csrf");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", Role.DOCTOR);
        session.setAttribute("accessToken", "signed-token");
        session.setAttribute("csrfToken", "csrf");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertEquals("请输入有效编号", session.getAttribute("flash"));
    }

    @Test
    void browserUtf8FormPreservesChineseDiagnosisAndAdvice() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        AtomicReference<String> submitted = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/doctor/appointments/" + id + "/complete", exchange -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            int count;
            while ((count = exchange.getRequestBody().read(bytes)) != -1) {
                body.write(bytes, 0, count);
            }
            submitted.set(new String(body.toByteArray(), StandardCharsets.UTF_8));
            byte[] reply = "{\"code\":\"OK\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            String body = "csrfToken=csrf&appointmentId=" + id + "&complaint="
                    + URLEncoder.encode("咽痛", "UTF-8") + "&diagnosis="
                    + URLEncoder.encode("急性咽炎", "UTF-8") + "&advice="
                    + URLEncoder.encode("多喝温水，三日复诊", "UTF-8");
            BrowserFormRequest request = new BrowserFormRequest(body);
            request.setContextPath("/portal");
            request.setServletPath("/doctor/complete");
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("role", Role.DOCTOR);
            session.setAttribute("accessToken", "signed-token");
            session.setAttribute("csrfToken", "csrf");
            request.setSession(session);
            PortalServlet servlet = new PortalServlet(
                    new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api"),
                    new CloudServiceFactory());

            servlet.service(request, new MockHttpServletResponse());

            assertEquals("UTF-8", request.getCharacterEncoding());
            assertNotNull(submitted.get());
            JsonNode json = new ObjectMapper().readTree(submitted.get());
            assertEquals("急性咽炎", json.path("diagnosis").asText());
            assertEquals("多喝温水，三日复诊", json.path("advice").asText());
        } finally {
            server.stop(0);
        }
    }

    private static final class BrowserFormRequest extends MockHttpServletRequest {
        private Map<String, String> parameters;

        private BrowserFormRequest(String body) {
            super("POST", "/portal/doctor/complete");
            setContent(body.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public String getContentType() {
            return "application/x-www-form-urlencoded";
        }

        @Override
        public String getParameter(String name) {
            if (parameters == null) {
                parameters = new HashMap<String, String>();
                String encoding = getCharacterEncoding() == null ? "ISO-8859-1" : getCharacterEncoding();
                try {
                    for (String pair : new String(getContentAsByteArray(), StandardCharsets.US_ASCII).split("&")) {
                        String[] parts = pair.split("=", 2);
                        parameters.put(URLDecoder.decode(parts[0], encoding),
                                URLDecoder.decode(parts.length == 2 ? parts[1] : "", encoding));
                    }
                } catch (java.io.UnsupportedEncodingException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            return parameters.get(name);
        }
    }
}
