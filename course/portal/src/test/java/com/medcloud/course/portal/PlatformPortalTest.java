package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.Role;
import com.medcloud.course.portal.factory.CloudServiceFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class PlatformPortalTest {
    @Test void wildcardServletMappingResolvesPathInfoForStatusRoute() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            MockHttpServletRequest request = f.statusRequest();
            request.setServletPath("/platform"); request.setPathInfo("/tenant/status");
            MockHttpServletResponse response = new MockHttpServletResponse();
            f.servlet.service(request, response);
            assertEquals(1, f.calls.size());
            assertEquals("PUT /platform/tenants/" + PortalFixture.ID + "/status", f.calls.get(0));
            assertEquals("/portal/platform", response.getRedirectedUrl());
        }
    }

    @Test void unknownWildcardGetChildNeverLoadsPrivateWorkspace() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            MockHttpServletRequest request = f.request("GET", "/platform", Role.PLATFORM_ADMIN);
            request.setPathInfo("/unknown"); MockHttpServletResponse response = new MockHttpServletResponse();
            f.servlet.service(request, response);
            assertEquals(404, response.getStatus()); assertEquals(0, f.calls.size());
        }
    }
    @Test void statusChangeSendsOnlyStatusFieldsAndPreservesLegalQuery() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            MockHttpServletRequest request = f.statusRequest();
            request.addParameter("page", "2"); request.addParameter("size", "10");
            request.addParameter("statsFrom", "2030-05-01"); request.addParameter("statsTo", "2030-05-02");
            request.addParameter("name", "伪造医院名"); request.addParameter("tenantId", "forged");
            MockHttpServletResponse response = new MockHttpServletResponse();
            f.servlet.service(request, response);
            String path = "/platform/tenants/" + PortalFixture.ID + "/status";
            assertEquals(1, f.calls.size());
            assertEquals("PUT " + path, f.calls.get(0));
            assertEquals("application/json; charset=UTF-8", f.contentTypes.get(path));
            JsonNode body = new ObjectMapper().readTree(f.bodies.get(path));
            assertEquals(2, body.size()); assertEquals("DISABLED", body.path("status").asText());
            assertEquals("ACTIVE", body.path("expectedStatus").asText());
            assertEquals("UTF-8", request.getCharacterEncoding());
            assertEquals("/portal/platform?page=2&size=10&statsFrom=2030-05-01&statsTo=2030-05-02", response.getRedirectedUrl());
            assertNotNull(request.getSession().getAttribute("flash"));
        }
    }

    @Test void csrfAndRoleChecksRunBeforeAnyPlatformCalls() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            MockHttpServletRequest forged = f.statusRequest(); forged.setParameter("csrfToken", "forged");
            MockHttpServletResponse response = new MockHttpServletResponse(); f.servlet.service(forged, response);
            assertEquals(403, response.getStatus());
            MockHttpServletRequest wrongRole = f.statusRequest(); wrongRole.getSession().setAttribute("role", Role.PATIENT);
            response = new MockHttpServletResponse(); f.servlet.service(wrongRole, response); assertEquals(403, response.getStatus());
            wrongRole = f.request("GET", "/platform/anything", Role.DOCTOR);
            response = new MockHttpServletResponse(); f.servlet.service(wrongRole, response); assertEquals(403, response.getStatus());
            assertEquals(0, f.calls.size());
        }
    }

    @Test void invalidStatusAndIdNeverReachPaasAndUnsafeFiltersAreNotRedirected() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            for (String field : new String[] {"status", "expectedStatus", "id"}) {
                MockHttpServletRequest request = f.statusRequest(); request.setParameter(field, "../bad");
                request.addParameter("page", "-1"); request.addParameter("size", "101");
                request.addParameter("statsFrom", "2030-02-30"); request.addParameter("statsTo", "2030-03-01");
                MockHttpServletResponse response = new MockHttpServletResponse(); f.servlet.service(request, response);
                assertEquals("/portal/platform", response.getRedirectedUrl()); assertEquals(0, f.calls.size());
            }
        }
    }

    @Test void listFailureKeepsStatisticsAndInvalidPageNeverBroadensList() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            f.status.put("/platform/tenants", 500);
            MockHttpServletRequest request = f.request("GET", "/platform", Role.PLATFORM_ADMIN);
            f.servlet.service(request, new MockHttpServletResponse());
            assertNull(request.getAttribute("tenants")); assertNotNull(request.getAttribute("tenantListError"));
            assertNotNull(request.getAttribute("statistics"));
            f.calls.clear(); request = f.request("GET", "/platform", Role.PLATFORM_ADMIN); request.addParameter("page", "0");
            f.servlet.service(request, new MockHttpServletResponse());
            assertFalse(f.calls.contains("GET /platform/tenants")); assertNotNull(request.getAttribute("statistics"));
        }
    }

    @Test void platformLoadsPagedListAndFactoryPreservesOldActions() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            MockHttpServletRequest request = f.request("GET", "/platform", Role.PLATFORM_ADMIN);
            request.addParameter("page", "2"); request.addParameter("size", "10");
            f.servlet.service(request, new MockHttpServletResponse());
            assertEquals("page=2&size=10", f.queries.get("/platform/tenants")); assertNotNull(request.getAttribute("tenants"));
            CloudServiceFactory factory = new CloudServiceFactory();
            assertTrue(factory.forRole(Role.PLATFORM_ADMIN).allows("view-overview"));
            assertTrue(factory.forRole(Role.PLATFORM_ADMIN).allows("manage-tenants"));
            assertTrue(factory.forRole(Role.PLATFORM_ADMIN).allows("view-statistics"));
            assertTrue(factory.forRole(Role.HOSPITAL_ADMIN).allows("view-slots"));
            assertTrue(factory.forRole(Role.HOSPITAL_ADMIN).allows("view-statistics"));
        }
    }
}
