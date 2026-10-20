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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SlotSearchPortalTest {
    @Test void patientForwardsAllFiltersWithoutUnfilteredFallback() throws Exception {
        AtomicReference<String> query = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalog(server);
        server.createContext("/api/catalog/slots", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] reply = "{\"code\":\"OK\",\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply); exchange.close();
        });
        server.start();
        try {
            PortalServlet servlet = servlet(server);
            MockHttpServletRequest request = request("GET", "/patient", Role.PATIENT);
            request.addParameter("tenantId", "tenant a"); request.addParameter("departmentId", "dept a");
            request.addParameter("doctorId", "doc a"); request.addParameter("date", "2035-05-01");
            servlet.service(request, new MockHttpServletResponse());
            assertEquals("tenantId=tenant+a&departmentId=dept+a&doctorId=doc+a&date=2035-05-01", query.get());
            assertNotNull(request.getAttribute("hospitals"), "Patient workspace needs a hospital directory");
            assertEquals("甲医院", ((JsonNode) request.getAttribute("hospitals")).get(0).path("name").asText());
            assertEquals("tenantId=tenant+a&departmentId=dept+a&doctorId=doc+a&date=2035-05-01",
                    request.getAttribute("patientFilters"));
        } finally { server.stop(0); }
    }

    @Test void staleDepartmentDoesNotBecomeAnUnfilteredSearchAndHistoryRemainsVisible() throws Exception {
        AtomicReference<String> query = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalog(server);
        server.createContext("/api/catalog/slots", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery()); reply(exchange, "[]");
        });
        server.start();
        try {
            MockHttpServletRequest request = request("GET", "/patient", Role.PATIENT);
            request.addParameter("tenantId", "tenant a"); request.addParameter("departmentId", "other-tenant-dept");
            servlet(server).service(request, new MockHttpServletResponse());
            assertNull(query.get(), "Never drop a forged or stale filter and broaden the slot query");
            assertNotNull(request.getAttribute("error"));
            assertEquals("", request.getAttribute("departmentId"));
            assertNotNull(request.getAttribute("appointments"), "Booking catalog errors must not hide history");
        } finally { server.stop(0); }
    }

    @Test void changedHospitalResetsLowerSelectionsForTheExplicitDirectoryRefresh() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalog(server); server.start();
        try {
            MockHttpServletRequest request = request("GET", "/patient", Role.PATIENT);
            request.addParameter("tenantId", "tenant a");
            request.addParameter("previousTenantId", "old-tenant");
            request.addParameter("departmentId", "old-dept"); request.addParameter("doctorId", "old-doc");
            request.addParameter("refreshDirectory", "1");
            servlet(server).service(request, new MockHttpServletResponse());
            assertEquals("", request.getAttribute("departmentId")); assertEquals("", request.getAttribute("doctorId"));
            assertEquals("tenantId=tenant+a", request.getAttribute("patientFilters"));
            assertNotNull(request.getAttribute("departments"));
            assertNull(request.getAttribute("slots")); assertNull(request.getAttribute("error"));
        } finally { server.stop(0); }
    }

    @Test void forgedPreviousParentMustNotEraseInvalidFiltersOnSlotQueries() throws Exception {
        AtomicReference<String> query = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalog(server);
        server.createContext("/api/catalog/slots", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery()); reply(exchange, "[]");
        });
        server.start();
        try {
            for (String previousKey : new String[] {"previousTenantId", "previousDepartmentId"}) {
                MockHttpServletRequest request = request("GET", "/patient", Role.PATIENT);
                request.addParameter("tenantId", "tenant a");
                request.addParameter("departmentId", "previousDepartmentId".equals(previousKey) ? "dept a" : "other-tenant-dept");
                request.addParameter("doctorId", "other-tenant-doctor");
                request.addParameter(previousKey, "forged");
                servlet(server).service(request, new MockHttpServletResponse());
                assertNull(query.get(), previousKey + " must not bypass catalog membership validation");
                assertNotNull(request.getAttribute("error"));
            }
        } finally { server.stop(0); }
    }

    @Test void unavailableDirectoryDoesNotHideAppointmentsOrSelectedRecord() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String id = "12345678-1234-1234-1234-123456789012";
        server.createContext("/api/catalog/hospitals", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.createContext("/api/patient/appointments", exchange -> {
            String path = exchange.getRequestURI().getPath();
            reply(exchange, path.endsWith("/record") ? "{\"diagnosis\":\"虚构诊断\"}"
                    : path.endsWith("/order") ? "{\"status\":\"UNPAID\",\"amount\":39,\"businessWritable\":false}"
                    : "{\"items\":[{\"id\":\"" + id + "\",\"status\":\"COMPLETED\",\"tenantStatus\":\"DISABLED\",\"businessWritable\":false}],\"total\":1,\"page\":1,\"size\":20}");
        });
        server.start();
        try {
            MockHttpServletRequest request = request("GET", "/patient", Role.PATIENT);
            request.addParameter("appointmentId", id);
            servlet(server).service(request, new MockHttpServletResponse());
            assertNotNull(request.getAttribute("error"));
            assertNotNull(request.getAttribute("appointments")); assertNotNull(request.getAttribute("record"));
            assertNotNull(request.getAttribute("order"));
            assertFalse(((JsonNode) request.getAttribute("appointments")).get(0).path("businessWritable").asBoolean());
            assertFalse(((JsonNode) request.getAttribute("order")).path("businessWritable").asBoolean());
        } finally { server.stop(0); }
    }

    private static void catalog(HttpServer server) {
        server.createContext("/api/catalog/hospitals", exchange -> reply(exchange, "[{\"id\":\"tenant a\",\"name\":\"甲医院\"}]"));
        server.createContext("/api/catalog/departments", exchange -> reply(exchange, "[{\"id\":\"dept a\",\"name\":\"内科\"}]"));
        server.createContext("/api/catalog/doctors", exchange -> reply(exchange, "[{\"id\":\"doc a\",\"name\":\"甲医生\"}]"));
        server.createContext("/api/patient/appointments", exchange -> reply(exchange, "{\"items\":[],\"total\":0,\"page\":1,\"size\":20}"));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String data) throws java.io.IOException {
        byte[] body = ("{\"code\":\"OK\",\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    }

    @Test void hospitalSlotFormUsesOwnSessionAndCsrf() throws Exception {
        AtomicReference<String> body = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/hospital/slots", exchange -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[512]; int n;
            while ((n = exchange.getRequestBody().read(buffer)) != -1) bytes.write(buffer, 0, n);
            body.set(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            byte[] reply = "{\"code\":\"OK\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply); exchange.close();
        });
        server.start();
        try {
            PortalServlet servlet = servlet(server);
            MockHttpServletRequest request = request("POST", "/hospital/slot/create", Role.HOSPITAL_ADMIN);
            request.addParameter("csrfToken", "csrf"); request.addParameter("tenantId", "forged");
            request.addParameter("departmentId", "11111111-1111-1111-1111-111111111111");
            request.addParameter("doctorId", "22222222-2222-2222-2222-222222222222");
            request.addParameter("date", "2035-05-01"); request.addParameter("startTime", "09:00");
            request.addParameter("endTime", "10:00"); request.addParameter("capacity", "2"); request.addParameter("fee", "30.00");
            servlet.service(request, new MockHttpServletResponse());
            JsonNode json = new ObjectMapper().readTree(body.get());
            assertEquals("11111111-1111-1111-1111-111111111111", json.path("departmentId").asText());
            assertFalse(json.has("tenantId"));
        } finally { server.stop(0); }
    }

    private static PortalServlet servlet(HttpServer server) {
        return new PortalServlet(new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api"), new CloudServiceFactory());
    }
    private static MockHttpServletRequest request(String method, String path, Role role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/portal" + path);
        request.setContextPath("/portal"); request.setServletPath(path);
        MockHttpSession session = new MockHttpSession(); session.setAttribute("role", role);
        session.setAttribute("accessToken", "token"); session.setAttribute("csrfToken", "csrf"); request.setSession(session);
        return request;
    }
}
