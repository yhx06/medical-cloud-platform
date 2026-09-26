package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.contract.Role;
import com.medcloud.course.portal.factory.CloudServiceFactory;
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
    void cloudFactorySelectsOnlyRoleAllowedActions() {
        CloudServiceFactory factory = new CloudServiceFactory();
        assertEquals("/patient", factory.forRole(Role.PATIENT).getHomePath());
        assertTrue(factory.forRole(Role.PATIENT).allows("book"));
        assertFalse(factory.forRole(Role.PATIENT).allows("complete"));
        assertTrue(factory.forRole(Role.DOCTOR).allows("complete"));
        assertFalse(factory.forRole(Role.HOSPITAL_ADMIN).allows("pay"));
        assertEquals("/platform", factory.forRole(Role.PLATFORM_ADMIN).getHomePath());
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
}
