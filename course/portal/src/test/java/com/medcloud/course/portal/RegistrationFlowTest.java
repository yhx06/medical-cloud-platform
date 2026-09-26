package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.*;

class RegistrationFlowTest {
    @Test
    void registrationPageIsPublicAndCreatesCsrfToken() throws Exception {
        PortalServlet servlet = new PortalServlet(new PaasClient("http://127.0.0.1:1/api"), new CloudServiceFactory());
        MockHttpServletRequest request = request("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(request, response);
        assertEquals("/WEB-INF/views/register.jsp", response.getForwardedUrl());
        assertNotNull(request.getSession().getAttribute("csrfToken"));
    }

    @Test
    void confirmationMismatchAndInvalidCsrfNeverSendPasswordToPaas() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(calls, new AtomicReference<String>());
        try {
            PortalServlet servlet = servlet(server);
            MockHttpServletRequest mismatch = request("POST");
            mismatch.setSession(session());
            mismatch.addParameter("username", "new_patient");
            mismatch.addParameter("password", "Secret123");
            mismatch.addParameter("confirmPassword", "different");
            mismatch.addParameter("csrfToken", "csrf");
            MockHttpServletResponse mismatchResponse = new MockHttpServletResponse();
            servlet.service(mismatch, mismatchResponse);
            assertEquals("/WEB-INF/views/register.jsp", mismatchResponse.getForwardedUrl());
            assertEquals(0, calls.get());
            assertFalse(String.valueOf(mismatch.getAttribute("error")).contains("Secret123"));

            MockHttpServletRequest forged = request("POST");
            forged.setSession(session());
            forged.addParameter("username", "new_patient");
            forged.addParameter("password", "Secret123");
            forged.addParameter("confirmPassword", "Secret123");
            forged.addParameter("csrfToken", "invalid");
            MockHttpServletResponse forgedResponse = new MockHttpServletResponse();
            servlet.service(forged, forgedResponse);
            assertEquals(403, forgedResponse.getStatus());
            assertEquals(0, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registrationSendsOnlyCredentialsAndRedirectsToLoginWithoutPasswordEcho() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<String>();
        HttpServer server = server(calls, body);
        try {
            PortalServlet servlet = servlet(server);
            MockHttpServletRequest request = request("POST");
            request.setSession(session());
            request.addParameter("username", "new_patient");
            request.addParameter("password", "Secret123");
            request.addParameter("confirmPassword", "Secret123");
            request.addParameter("role", "PLATFORM_ADMIN");
            request.addParameter("tenantId", "other-hospital");
            request.addParameter("status", "ACTIVE");
            request.addParameter("csrfToken", "csrf");
            MockHttpServletResponse response = new MockHttpServletResponse();
            servlet.service(request, response);
            assertEquals(1, calls.get());
            JsonNode sent = new ObjectMapper().readTree(body.get());
            assertEquals("new_patient", sent.path("username").asText());
            assertEquals("Secret123", sent.path("password").asText());
            assertEquals(2, sent.size());
            assertEquals("/portal/login", response.getRedirectedUrl());
            assertFalse(response.getContentAsString().contains("Secret123"));
            assertNull(request.getSession().getAttribute("role"));
        } finally {
            server.stop(0);
        }
    }

    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/portal/register");
        request.setContextPath("/portal");
        request.setServletPath("/register");
        return request;
    }

    private static MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("csrfToken", "csrf");
        return session;
    }

    private static PortalServlet servlet(HttpServer server) {
        return new PortalServlet(new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api"),
                new CloudServiceFactory());
    }

    private static HttpServer server(AtomicInteger calls, AtomicReference<String> body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/register", exchange -> {
            calls.incrementAndGet();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int count;
            while ((count = exchange.getRequestBody().read(chunk)) != -1) bytes.write(chunk, 0, count);
            body.set(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            byte[] reply = "{\"code\":\"OK\",\"data\":{\"id\":\"patient-1\",\"username\":\"new_patient\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        return server;
    }
}
