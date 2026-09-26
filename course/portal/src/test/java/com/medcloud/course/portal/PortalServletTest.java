package com.medcloud.course.portal;

import com.medcloud.course.contract.Role;
import com.medcloud.course.portal.factory.CloudServiceFactory;
import com.medcloud.course.portal.web.PaasClient;
import com.medcloud.course.portal.web.PortalServlet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PortalServletTest {
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
}
