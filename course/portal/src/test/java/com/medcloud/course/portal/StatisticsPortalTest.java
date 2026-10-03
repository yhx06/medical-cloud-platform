package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.Role;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsPortalTest {
    @Test void statisticsAndOrderFiltersRemainIndependentAndNeverSendTenant() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            MockHttpServletRequest request = f.request("GET", "/hospital", Role.HOSPITAL_ADMIN);
            request.addParameter("status", "PAID"); request.addParameter("from", "2030-05-21"); request.addParameter("to", "2030-05-22");
            request.addParameter("statsFrom", "2030-05-01"); request.addParameter("statsTo", "2030-05-02"); request.addParameter("tenantId", "forged");
            f.servlet.service(request, new MockHttpServletResponse());
            assertEquals("status=PAID&from=2030-05-21&to=2030-05-22", f.queries.get("/hospital/orders"));
            assertEquals("from=2030-05-01&to=2030-05-02", f.queries.get("/hospital/statistics"));
            assertNotNull(request.getAttribute("statistics"));
        }
    }

    @Test void statisticsFailureKeepsOrdersWithoutFakeZeroReport() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            f.status.put("/hospital/statistics", 500);
            MockHttpServletRequest request = f.request("GET", "/hospital", Role.HOSPITAL_ADMIN);
            f.servlet.service(request, new MockHttpServletResponse());
            assertEquals(1, ((JsonNode) request.getAttribute("orders")).size());
            assertNull(request.getAttribute("statistics")); assertNotNull(request.getAttribute("statisticsError"));
            assertFalse(request.getAttribute("statisticsError").toString().contains("SQL"));
        }
    }

    @Test void invalidStatisticsFiltersNeverFallBackToDefaultQuery() throws Exception {
        String[][] ranges = {{"2030-02-30", "2030-03-01"}, {"2030-05-01", ""}, {"2030-05-02", "2030-05-01"},
                {"2030-01-01", "2031-01-02"}, {"2030-5-01", "2030-05-02"}, {"9999-12-31", "9999-12-31"}, {"<script>", "2030-05-01"}};
        try (PortalFixture f = new PortalFixture()) {
            for (String[] range : ranges) {
                f.calls.clear(); MockHttpServletRequest request = f.request("GET", "/hospital", Role.HOSPITAL_ADMIN);
                request.addParameter("statsFrom", range[0]); request.addParameter("statsTo", range[1]);
                f.servlet.service(request, new MockHttpServletResponse());
                assertFalse(f.calls.contains("GET /hospital/statistics"), range[0]);
                assertNotNull(request.getAttribute("statisticsError")); assertNotNull(request.getAttribute("orders"));
            }
        }
    }

    @Test void resetUsesDefaultStatisticsWhileRetainingValidOrderConditions() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            MockHttpServletRequest request = f.request("GET", "/hospital", Role.HOSPITAL_ADMIN);
            request.addParameter("from", "2030-05-21"); request.addParameter("to", "2030-05-22"); request.addParameter("status", "UNPAID");
            f.servlet.service(request, new MockHttpServletResponse());
            assertTrue(f.calls.contains("GET /hospital/statistics")); assertNull(f.queries.get("/hospital/statistics"));
            assertEquals("status=UNPAID&from=2030-05-21&to=2030-05-22", f.queries.get("/hospital/orders"));
            assertEquals("2030-05-01", request.getAttribute("statsFrom"));
        }
    }

    @Test void invalidOrdersDoNotBlockValidStatisticsOrBroadenOrderQuery() throws Exception {
        try (PortalFixture f = new PortalFixture()) {
            MockHttpServletRequest request = f.request("GET", "/hospital", Role.HOSPITAL_ADMIN); request.addParameter("status", "UNKNOWN");
            request.addParameter("statsFrom", "2030-05-01"); request.addParameter("statsTo", "2030-05-02");
            f.servlet.service(request, new MockHttpServletResponse());
            assertFalse(f.calls.contains("GET /hospital/orders")); assertNotNull(request.getAttribute("ordersError"));
            assertNotNull(request.getAttribute("statistics"));
        }
    }
}
