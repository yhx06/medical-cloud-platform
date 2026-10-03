package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.contract.Role;
import com.medcloud.course.portal.factory.CloudServiceFactory;
import com.medcloud.course.portal.web.PortalServlet;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import com.medcloud.course.portal.web.PaasClient;
import com.medcloud.course.portal.web.PaasUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalFlowTest {
    @Test
    void doctorCompletionPostsUtf8ClinicalFields() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        AtomicReference<String> body = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/doctor/appointments/" + id + "/complete", exchange -> {
            byte[] bytes = new byte[4096];
            int count = exchange.getRequestBody().read(bytes);
            body.set(new String(bytes, 0, count, StandardCharsets.UTF_8));
            byte[] response = "{\"code\":\"OK\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            PortalServlet servlet = new PortalServlet(new PaasClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api"), new CloudServiceFactory());
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("role", Role.DOCTOR); session.setAttribute("accessToken", "signed-token");
            session.setAttribute("csrfToken", "csrf");
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/portal/doctor/complete");
            request.setContextPath("/portal"); request.setServletPath("/doctor/complete"); request.setSession(session);
            request.addParameter("appointmentId", id); request.addParameter("csrfToken", "csrf");
            request.addParameter("complaint", "头痛发热"); request.addParameter("diagnosis", "感冒");
            request.addParameter("advice", "补水休息"); request.addParameter("notes", "观察两日");
            servlet.service(request, new MockHttpServletResponse());
            assertTrue(body.get().contains("\"complaint\":\"头痛发热\""));
            assertTrue(body.get().contains("\"notes\":\"观察两日\""));
        } finally { server.stop(0); }
    }
    @Test
    void patientCancellationPostsOnlyValidatedAppointmentWithCsrf() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        AtomicReference<String> called = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/patient/appointments/" + id + "/cancel", exchange -> {
            called.set(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"code\":\"OK\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            PortalServlet servlet = new PortalServlet(new PaasClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api"), new CloudServiceFactory());
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("role", Role.PATIENT);
            session.setAttribute("accessToken", "signed-token");
            session.setAttribute("csrfToken", "csrf");
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/portal/patient/cancel");
            request.setContextPath("/portal"); request.setServletPath("/patient/cancel"); request.setSession(session);
            request.addParameter("appointmentId", id); request.addParameter("csrfToken", "csrf");
            MockHttpServletResponse response = new MockHttpServletResponse();
            servlet.service(request, response);
            assertEquals("POST Bearer signed-token", called.get());
            assertEquals("/portal/patient", response.getRedirectedUrl());
            assertEquals("预约已取消", session.getAttribute("flash"));
        } finally { server.stop(0); }
    }

    @Test
    void cloudFactorySelectsOnlyRoleAllowedActions() {
        CloudServiceFactory factory = new CloudServiceFactory();
        assertEquals("/patient", factory.forRole(Role.PATIENT).getHomePath());
        assertTrue(factory.forRole(Role.PATIENT).allows("book"));
        assertFalse(factory.forRole(Role.PATIENT).allows("complete"));
        assertTrue(factory.forRole(Role.DOCTOR).allows("complete"));
        assertFalse(factory.forRole(Role.HOSPITAL_ADMIN).allows("pay"));
        assertEquals("/platform", factory.forRole(Role.PLATFORM_ADMIN).getHomePath());
        assertTrue(factory.forRole(Role.PLATFORM_ADMIN).allows("view-overview"));
        assertTrue(factory.forRole(Role.PLATFORM_ADMIN).allows("manage-tenants"));
        assertTrue(factory.forRole(Role.PLATFORM_ADMIN).allows("view-statistics"));
        assertTrue(factory.forRole(Role.HOSPITAL_ADMIN).allows("view-slots"));
        assertTrue(factory.forRole(Role.HOSPITAL_ADMIN).allows("view-statistics"));
    }

    @Test
    void gatewayCallsWildFlyHttpJsonWithBearerToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> loginBody = new AtomicReference<String>();
        AtomicReference<String> bearer = new AtomicReference<String>();
        server.createContext("/api/auth/login", exchange -> {
            byte[] body = new byte[1024];
            int read = exchange.getRequestBody().read(body);
            loginBody.set(new String(body, 0, read, StandardCharsets.UTF_8));
            byte[] response = "{\"code\":\"OK\",\"message\":\"logged in\",\"data\":{\"accessToken\":\"signed-token\",\"username\":\"patient_a\",\"role\":\"PATIENT\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/catalog/slots", exchange -> {
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"code\":\"OK\",\"message\":\"slots\",\"data\":[{\"id\":\"slot-a\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            PaasClient client = new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
            LoginResponse login = client.login(new LoginRequest("patient_a", "Demo@123456"));
            JsonNode slots = client.get("/catalog/slots?tenantId=tenant-a", login.getAccessToken());
            assertTrue(loginBody.get().contains("\"username\":\"patient_a\""));
            assertEquals("Bearer signed-token", bearer.get());
            assertEquals("slot-a", slots.get(0).get("id").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unavailablePaasProducesStableErrorWithoutCredentialLeak() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PaasClient client = new PaasClient("http://127.0.0.1:" + closedPort + "/api");
        PaasUnavailableException failure = assertThrows(PaasUnavailableException.class,
                () -> client.login(new LoginRequest("patient_a", "Secret@123456")));
        assertFalse(failure.getMessage().contains("Secret@123456"));
        assertEquals("诊疗服务暂时不可用，请稍后重试", failure.getMessage());
    }

    @Test
    void emptyPostStillUsesJsonContentTypeForWildFly() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> contentType = new AtomicReference<String>();
        server.createContext("/api/doctor/appointments/abc/start", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = "{\"code\":\"OK\",\"data\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            PaasClient client = new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
            client.post("/doctor/appointments/abc/start", "signed-token", null);
            assertTrue(contentType.get() != null && contentType.get().startsWith("application/json"));
        } finally {
            server.stop(0);
        }
    }
}
