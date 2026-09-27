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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HospitalPortalTest {
    @Test
    void hospitalEditUsesPutUtf8JsonAndValidCsrf() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        AtomicReference<String> method = new AtomicReference<String>();
        AtomicReference<String> body = new AtomicReference<String>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/hospital/departments/" + id, exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int n;
            while ((n = exchange.getRequestBody().read(buffer)) != -1) bytes.write(buffer, 0, n);
            body.set(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            byte[] reply = "{\"code\":\"OK\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply); exchange.close();
        });
        server.start();
        try {
            PortalServlet servlet = new PortalServlet(new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api"), new CloudServiceFactory());
            MockHttpServletRequest request = request("/hospital/department/edit", id, "csrf");
            servlet.service(request, new MockHttpServletResponse());
            assertEquals("PUT", method.get());
            JsonNode json = new ObjectMapper().readTree(body.get());
            assertEquals("心内科", json.path("name").asText());
            assertEquals("ACTIVE", json.path("status").asText());
            assertFalse(json.has("tenantId"));
            assertEquals(1, calls.get());
            MockHttpServletRequest forged = request("/hospital/department/edit", id, "forged");
            MockHttpServletResponse denied = new MockHttpServletResponse();
            servlet.service(forged, denied);
            assertEquals(403, denied.getStatus());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void hospitalFiltersOrdersWithoutSendingSubmittedTenant() throws Exception {
        AtomicReference<String> orderQuery = new AtomicReference<String>();
        AtomicReference<String> authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/hospital/orders")) {
                orderQuery.set(exchange.getRequestURI().getRawQuery());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            byte[] response = "{\"code\":\"OK\",\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            PortalServlet servlet = new PortalServlet(
                    new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api"),
                    new CloudServiceFactory());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/portal/hospital");
            request.setContextPath("/portal");
            request.setServletPath("/hospital");
            request.addParameter("status", "PAID");
            request.addParameter("from", "2030-05-21");
            request.addParameter("to", "2030-05-22");
            request.addParameter("tenantId", "other-tenant");
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("role", Role.HOSPITAL_ADMIN);
            session.setAttribute("accessToken", "signed-token");
            session.setAttribute("csrfToken", "csrf");
            request.setSession(session);

            servlet.service(request, new MockHttpServletResponse());

            assertEquals("status=PAID&from=2030-05-21&to=2030-05-22", orderQuery.get());
            assertEquals("Bearer signed-token", authorization.get());
            assertFalse(orderQuery.get().contains("tenantId"));
            assertEquals(0, ((JsonNode) request.getAttribute("orders")).size());
        } finally {
            server.stop(0);
        }
    }

    private static MockHttpServletRequest request(String path, String id, String csrf) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/portal" + path);
        request.setContextPath("/portal"); request.setServletPath(path);
        request.addParameter("id", id); request.addParameter("name", "心内科");
        request.addParameter("status", "ACTIVE"); request.addParameter("csrfToken", csrf);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("role", Role.HOSPITAL_ADMIN);
        session.setAttribute("accessToken", "token");
        session.setAttribute("csrfToken", "csrf"); request.setSession(session);
        return request;
    }
}
