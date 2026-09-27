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
        } finally { server.stop(0); }
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
