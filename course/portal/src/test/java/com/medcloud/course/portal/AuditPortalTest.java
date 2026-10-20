package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.Role;
import com.medcloud.course.portal.factory.CloudServiceFactory;
import com.medcloud.course.portal.web.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditPortalTest {
    static final String A = "11111111-1111-1111-1111-111111111111";
    static final String B = "22222222-2222-2222-2222-222222222222";
    static final String[] PRIVATE = {"auditPage", "auditError", "auditFrom", "auditTo", "auditEventType", "auditResult",
            "auditPageNumber", "auditSize", "auditTenantId", "auditHospitals", "auditDirectoryError", "auditTenantName"};

    @Test void hospitalAndPlatformLoadOwnAuditEndpoint() throws Exception {
        try (Fixture f = new Fixture()) {
            for (Role role : new Role[]{Role.HOSPITAL_ADMIN, Role.PLATFORM_ADMIN}) {
                String home = role == Role.HOSPITAL_ADMIN ? "/hospital" : "/platform";
                MockHttpServletRequest r = f.request(home, role);
                f.servlet.service(r, new MockHttpServletResponse());
                assertNotNull(r.getAttribute("auditPage")); assertNull(r.getAttribute("auditError"));
                assertTrue(f.calls.contains(home + "/audit-events"));
                assertEquals("Bearer fictional-test-token", f.authorization.get(home + "/audit-events"));
                assertFalse(f.queries.get(home + "/audit-events").contains("tenantId"));
                assertEquals("Asia/Shanghai", ((JsonNode) r.getAttribute("auditPage")).path("timezone").asText());
            }
        }
    }

    @Test void auditFiltersAreIndependentOfStatsOrdersAndTenantPagination() throws Exception {
        try (Fixture f = new Fixture()) {
            MockHttpServletRequest r = f.request("/hospital", Role.HOSPITAL_ADMIN);
            filters(r); r.addParameter("status", "PAID"); r.addParameter("from", "2030-05-21"); r.addParameter("to", "2030-05-22");
            r.addParameter("statsFrom", "2030-05-03"); r.addParameter("statsTo", "2030-05-04");
            f.servlet.service(r, new MockHttpServletResponse());
            assertEquals("status=PAID&from=2030-05-21&to=2030-05-22&page=1&size=20", f.queries.get("/hospital/orders"));
            assertEquals("from=2030-05-03&to=2030-05-04", f.queries.get("/hospital/statistics"));
            assertEquals("from=2030-05-01&to=2030-05-02&eventType=LOGIN&result=FAILED&page=2&size=1", f.queries.get("/hospital/audit-events"));
            MockHttpServletRequest p = f.request("/platform", Role.PLATFORM_ADMIN); filters(p);
            p.addParameter("page", "3"); p.addParameter("size", "20"); p.addParameter("auditTenantId", B);
            f.servlet.service(p, new MockHttpServletResponse());
            assertNotNull(p.getAttribute("tenants")); assertEquals("院外页 &lt;script&gt;unsafe&lt;/script&gt;", p.getAttribute("auditTenantName"));
            assertEquals(101, ((JsonNode)p.getAttribute("auditHospitals")).size());
            assertTrue(f.tenantQueries.contains("page=3&size=20"));
            assertTrue(f.tenantQueries.contains("page=1&size=100")); assertTrue(f.tenantQueries.contains("page=2&size=100"));
            assertTrue(f.queries.get("/platform/audit-events").contains("tenantId=" + B));
            assertEquals("Bearer fictional-test-token", f.authorization.get("/platform/tenants"));
        }
    }

    @Test void invalidFiltersDoNotRequestDefaultRange() throws Exception {
        String[][] bad = {{"auditFrom", "<script>bad</script>"}, {"auditFrom", "2030-02-30"}, {"auditTo", "2030-04-30"},
                {"auditTo", "2031-05-02"}, {"auditEventType", "READ"}, {"auditResult", "SUCCESSFUL"}, {"auditPage", "0"},
                {"auditPage", "2147483647"}, {"auditSize", "101"}, {"auditTenantId", A}, {"auditUnknown", "x"}};
        try (Fixture f = new Fixture()) {
            for (String[] pair : bad) {
                f.calls.clear(); MockHttpServletRequest r = f.request("/hospital", Role.HOSPITAL_ADMIN);
                filters(r); r.setParameter(pair[0], pair[1]);
                if (pair[0].equals("auditPage") && pair[1].equals("2147483647")) r.setParameter("auditSize", "2");
                f.servlet.service(r, new MockHttpServletResponse());
                assertNotNull(r.getAttribute("auditError"), Arrays.toString(pair)); assertNull(r.getAttribute("auditPage"));
                assertFalse(f.calls.contains("/hospital/audit-events"), Arrays.toString(pair)); assertNotNull(r.getAttribute("orders"));
                if (pair[1].startsWith("<")) assertEquals("&lt;script&gt;bad&lt;/script&gt;", r.getAttribute("auditFrom"));
            }
            for (String key : new String[]{"auditFrom", "auditTo", "auditEventType", "auditResult", "auditPage", "auditSize", "auditTenantId"}) {
                f.calls.clear(); MockHttpServletRequest r = f.request("/platform", Role.PLATFORM_ADMIN); filters(r);
                r.setParameter(key, new String[]{"2030-05-01", "2030-05-02"}); f.servlet.service(r, new MockHttpServletResponse());
                assertNotNull(r.getAttribute("auditError"), key); assertFalse(f.calls.contains("/platform/audit-events"), key);
            }
            MockHttpServletRequest partial = f.request("/hospital", Role.HOSPITAL_ADMIN); partial.addParameter("auditFrom", "2030-05-01");
            f.calls.clear(); f.servlet.service(partial, new MockHttpServletResponse());
            assertNotNull(partial.getAttribute("auditError")); assertFalse(f.calls.contains("/hospital/audit-events"));
        }
    }

    @Test void audit500OrTimeoutRetainsOtherSections() throws Exception {
        try (Fixture f = new Fixture()) {
            for (boolean timeout : new boolean[]{false, true}) {
                f.auditStatus = timeout ? 200 : 500; f.delay = timeout ? 8500 : 0;
                MockHttpServletRequest r = f.request("/hospital", Role.HOSPITAL_ADMIN);
                Object originalOrders = new Object(), originalStats = new Object();
                r.setAttribute("orders", originalOrders); r.setAttribute("statistics", originalStats);
                new AuditWorkspace(f.client).load(r, "fictional-test-token", Role.HOSPITAL_ADMIN);
                assertNotNull(r.getAttribute("auditError")); assertSame(originalOrders, r.getAttribute("orders"));
                assertSame(originalStats, r.getAttribute("statistics")); assertNull(r.getAttribute("auditPage"));
                assertFalse(r.getAttribute("auditError").toString().contains("private"));
            }
            f.delay = 0; MockHttpServletRequest r = f.request("/hospital", Role.HOSPITAL_ADMIN);
            f.servlet.service(r, new MockHttpServletResponse());
            assertNotNull(r.getAttribute("orders")); assertNotNull(r.getAttribute("statistics")); assertNotNull(r.getAttribute("auditPage"));
        }
    }

    @Test void audit401ClearsAllPrivateAttributes() throws Exception {
        try (Fixture f = new Fixture()) {
            for (Role role : new Role[]{Role.HOSPITAL_ADMIN, Role.PLATFORM_ADMIN}) {
                f.auditStatus = 401; MockHttpServletRequest r = f.request(role == Role.HOSPITAL_ADMIN ? "/hospital" : "/platform", role);
                for (String key : PRIVATE) r.setAttribute(key, "private");
                MockHttpSession session = (MockHttpSession) r.getSession(); MockHttpServletResponse response = new MockHttpServletResponse();
                f.servlet.service(r, response);
                assertTrue(session.isInvalid()); assertEquals("/WEB-INF/views/login.jsp", response.getForwardedUrl());
                for (String key : PRIVATE) assertNull(r.getAttribute(key), key);
                for (String key : new String[]{"orders", "statistics", "tenants", "username", "csrfToken", "hospitalContext"}) assertNull(r.getAttribute(key), key);
            }
            f.auditStatus = 200; f.directoryStatus = 401; MockHttpServletRequest r = f.request("/platform", Role.PLATFORM_ADMIN);
            MockHttpSession session = (MockHttpSession)r.getSession(); f.servlet.service(r, new MockHttpServletResponse());
            assertTrue(session.isInvalid()); for (String key : PRIVATE) assertNull(r.getAttribute(key), key);
            // Expire before the audit loader removes its own transient attributes: catches omissions in global cleanup.
            f.directoryStatus=200; f.earlyUnauthorized="/hospital/orders"; r=f.request("/hospital",Role.HOSPITAL_ADMIN);
            for(String key:PRIVATE)r.setAttribute(key,"private");session=(MockHttpSession)r.getSession();
            f.servlet.service(r,new MockHttpServletResponse());assertTrue(session.isInvalid());
            for(String key:PRIVATE)assertNull(r.getAttribute(key),key);
        }
    }

    @Test void auditDtoShapeFailureShowsErrorNotEmptyRows() throws Exception {
        try (Fixture f = new Fixture()) {
            for (String field : new String[]{"items", "total", "from", "to", "page", "size", "timezone"}) {
                f.auditData = new ObjectMapper().readTree(page(false)).deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)f.auditData).remove(field);
                MockHttpServletRequest r = f.request("/hospital", Role.HOSPITAL_ADMIN); f.servlet.service(r, new MockHttpServletResponse());
                assertNotNull(r.getAttribute("auditError"), field); assertNull(r.getAttribute("auditPage")); assertNotNull(r.getAttribute("orders"));
            }
            f.auditData = new ObjectMapper().readTree("{\"items\":[],\"total\":\"0\",\"from\":\"2030-05-01\",\"to\":\"2030-05-02\",\"page\":1,\"size\":20,\"timezone\":\"Asia/Shanghai\"}");
            MockHttpServletRequest r = f.request("/hospital", Role.HOSPITAL_ADMIN); f.servlet.service(r, new MockHttpServletResponse()); assertNotNull(r.getAttribute("auditError"));
            f.auditData = new ObjectMapper().readTree(page(false)); ((com.fasterxml.jackson.databind.node.ObjectNode)f.auditData.path("items").get(0)).remove("actorLabel");
            r = f.request("/hospital", Role.HOSPITAL_ADMIN); f.servlet.service(r, new MockHttpServletResponse()); assertNotNull(r.getAttribute("auditError"));
        }
    }

    @Test void serverRoleCheckPrecedesParsingAndDirectoryFailureIsIndependent() throws Exception {
        try (Fixture f = new Fixture()) {
            MockHttpServletRequest wrong = f.request("/hospital", Role.PATIENT); wrong.addParameter("auditFrom", "bad");
            MockHttpServletResponse response = new MockHttpServletResponse(); f.servlet.service(wrong, response);
            assertEquals(403, response.getStatus()); assertTrue(f.calls.isEmpty());
            wrong = f.request("/patient", Role.PATIENT); new AuditWorkspace(f.client).load(wrong, "fictional-test-token", Role.PATIENT);
            assertNotNull(wrong.getAttribute("auditError")); assertTrue(f.calls.isEmpty());
            f.directoryStatus = 500; MockHttpServletRequest p = f.request("/platform", Role.PLATFORM_ADMIN); p.addParameter("auditTenantId", B);
            f.servlet.service(p, new MockHttpServletResponse()); assertNotNull(p.getAttribute("auditPage"));
            assertNotNull(p.getAttribute("statistics")); assertNotNull(p.getAttribute("auditDirectoryError")); assertNull(p.getAttribute("auditHospitals"));
        }
    }

    @Test void platformStatusRedirectPreservesValidatedAuditFilters() throws Exception {
        try (Fixture f = new Fixture()) {
            MockHttpServletRequest r = f.request("/platform/tenant/status", Role.PLATFORM_ADMIN); r.setMethod("POST"); filters(r);
            r.addParameter("auditTenantId", B); r.addParameter("page", "3"); r.addParameter("size", "20");
            r.addParameter("id", A); r.addParameter("status", "DISABLED"); r.addParameter("expectedStatus", "ACTIVE"); r.addParameter("csrfToken", "fictional-csrf");
            MockHttpServletResponse response = new MockHttpServletResponse(); f.servlet.service(r,response);
            String redirect=response.getRedirectedUrl(); assertTrue(redirect.contains("auditFrom=2030-05-01"));
            assertTrue(redirect.contains("auditTo=2030-05-02")); assertTrue(redirect.contains("auditPage=2")); assertTrue(redirect.contains("auditSize=1"));
            assertTrue(redirect.contains("auditTenantId="+B)); assertTrue(redirect.contains("auditEventType=LOGIN")); assertTrue(redirect.contains("auditResult=FAILED"));
            r.setParameter("auditResult","INVALID"); response=new MockHttpServletResponse(); f.servlet.service(r,response);
            assertFalse(response.getRedirectedUrl().contains("audit")); assertTrue(response.getRedirectedUrl().contains("page=3"));
        }
    }

    @Test void legacyFixtureDistinguishesDirectoryWithoutErasingRawRequestsOrSize100Management() throws Exception {
        try(PortalFixture f=new PortalFixture()) {
            for(String[] pagination:new String[][]{{"2","10"},{"1","100"},{"0","20"}}) {
                f.calls.clear();f.rawReads.clear();f.auditDirectoryReads.clear();f.queries.clear();
                MockHttpServletRequest r=f.request("GET","/platform",Role.PLATFORM_ADMIN);
                r.addParameter("page",pagination[0]);r.addParameter("size",pagination[1]);f.servlet.service(r,new MockHttpServletResponse());
                assertTrue(f.rawReads.contains("GET /platform/audit-events?page=1&size=20"));
                assertTrue(f.rawReads.contains("GET /platform/tenants?page=1&size=100"));
                assertEquals(Collections.singletonList("GET /platform/tenants?page=1&size=100"),f.auditDirectoryReads);
                if(pagination[0].equals("0")) {assertFalse(f.calls.contains("GET /platform/tenants"));assertFalse(f.queries.containsKey("/platform/tenants"));}
                else {assertEquals("page="+pagination[0]+"&size="+pagination[1],f.queries.get("/platform/tenants"));assertEquals(1,Collections.frequency(f.calls,"GET /platform/tenants"));}
                if(pagination[1].equals("100"))assertEquals(2,Collections.frequency(f.rawReads,"GET /platform/tenants?page=1&size=100"),"Identical HTTP query remains visible twice, with distinct workflow phases");
            }
            f.status.put("/platform/tenants",401);MockHttpServletRequest r=f.request("GET","/platform",Role.PLATFORM_ADMIN);
            MockHttpSession s=(MockHttpSession)r.getSession();f.servlet.service(r,new MockHttpServletResponse());assertTrue(s.isInvalid());
        }
    }

    static void filters(MockHttpServletRequest r) {
        r.addParameter("auditFrom", "2030-05-01"); r.addParameter("auditTo", "2030-05-02");
        r.addParameter("auditEventType", "LOGIN"); r.addParameter("auditResult", "FAILED"); r.addParameter("auditPage", "2"); r.addParameter("auditSize", "1");
    }
    static String page(boolean empty) {
        return "{\"items\":" + (empty ? "[]" : "[{\"id\":\"safe-event\",\"requestId\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUV\",\"tenantId\":\"" + B + "\",\"tenantName\":\"院外页 <script>unsafe</script>\",\"actorRole\":\"PATIENT\",\"actorLabel\":\"患者（已脱敏） <img src=x>\",\"eventType\":\"LOGIN\",\"objectType\":\"ACCOUNT\",\"result\":\"FAILED\",\"reasonCode\":\"UNAUTHORIZED\",\"recordedAt\":\"2030-05-02T12:00:00+08:00\"}]")
                + ",\"total\":" + (empty ? "0" : "3") + ",\"from\":\"2030-05-01\",\"to\":\"2030-05-02\",\"page\":2,\"size\":1,\"timezone\":\"Asia/Shanghai\"}";
    }
    static final class Fixture implements AutoCloseable {
        final HttpServer server; final ExecutorService executor = Executors.newCachedThreadPool(); final PaasClient client; final PortalServlet servlet;
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>()), tenantQueries = Collections.synchronizedList(new ArrayList<String>());
        final Map<String,String> queries = new ConcurrentHashMap<String,String>(), authorization = new ConcurrentHashMap<String,String>();
        volatile int auditStatus = 200, directoryStatus = 200; volatile long delay; volatile JsonNode auditData; volatile String earlyUnauthorized;
        Fixture() throws Exception {
            auditData = new ObjectMapper().readTree(page(false)); server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
            server.createContext("/api/", e -> {
                String path = e.getRequestURI().getPath().substring(4), query = e.getRequestURI().getRawQuery(); calls.add(path);
                queries.put(path, query == null ? "" : query); String auth = e.getRequestHeaders().getFirst("Authorization"); authorization.put(path, auth == null ? "" : auth);
                int status = 200; String data = "[]";
                try {
                    if (path.endsWith("/audit-events")) { status = auditStatus; if (delay > 0) Thread.sleep(delay); data = auditData.toString(); }
                    else if (path.endsWith("/statistics")) {
                        data = PortalFixture.statisticsJson(path.startsWith("/hospital") ? "hospital" : "platform");
                        if ("from=2030-05-03&to=2030-05-04".equals(query)) {
                            com.fasterxml.jackson.databind.node.ObjectNode report=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(data);
                            report.put("from","2030-05-03");report.put("to","2030-05-04");data=report.toString();
                        }
                    }
                    else if (path.equals("/hospital/context")) data = PortalFixture.contextJson();
                    else if (path.equals("/hospital/orders")) data = "{\"items\":[{\"id\":\"fictional-order\",\"status\":\"PAID\",\"amount\":39}],\"total\":1,\"page\":1,\"size\":20}";
                    else if (path.equals("/platform/tenants")) {
                        tenantQueries.add(query == null ? "" : query); if (query != null && query.contains("size=100")) status = directoryStatus;
                        if ("page=1&size=100".equals(query)) {
                            StringBuilder rows = new StringBuilder(); for (int i=0;i<100;i++) { if(i>0)rows.append(','); rows.append("{\"id\":\"").append(String.format("%08d-1111-1111-1111-111111111111",i)).append("\",\"name\":\"虚构医院\",\"status\":\"ACTIVE\"}"); }
                            data = "{\"items\":[" + rows + "],\"total\":101,\"page\":1,\"size\":100}";
                        } else data = "{\"items\":[{\"id\":\"" + ("page=2&size=100".equals(query) ? B : A) + "\",\"name\":\"院外页 <script>unsafe</script>\",\"status\":\"DISABLED\"}],\"total\":101,\"page\":" + ("page=2&size=100".equals(query) ? "2" : "3") + ",\"size\":" + (query != null && query.contains("size=100") ? "100" : "20") + "}";
                    }
                    if(path.equals(earlyUnauthorized))status=401;
                    byte[] bytes = (status >= 400 ? "private error body" : "{\"code\":\"OK\",\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
                    e.sendResponseHeaders(status,bytes.length); e.getResponseBody().write(bytes);
                } catch (Exception ignored) { /* Timeout intentionally closes client before response. */ } finally { e.close(); }
            }); server.start(); client = new PaasClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api"); servlet = new PortalServlet(client,new CloudServiceFactory());
        }
        MockHttpServletRequest request(String path,Role role) {
            MockHttpServletRequest r = new MockHttpServletRequest("GET","/portal"+path); r.setServletPath(path); r.setContextPath("/portal");
            MockHttpSession s = new MockHttpSession(); s.setAttribute("role",role); s.setAttribute("accessToken","fictional-test-token"); s.setAttribute("csrfToken","fictional-csrf"); r.setSession(s); return r;
        }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
