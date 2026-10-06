package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.*;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.order.PaymentService;
import com.medcloud.course.paas.statistics.StatisticsService;
import com.medcloud.course.paas.tenant.PlatformTenantService;
import org.jboss.resteasy.core.Dispatcher;
import org.jboss.resteasy.mock.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AuditDispatchTest extends AuditFixture {
    Dispatcher dispatcher; AuthService auth;
    @BeforeEach void dispatchFixture() {
        auth=new AuthService(manager,SECRET); dispatcher=MockDispatcherFactory.createDispatcher();
        dispatcher.getProviderFactory().registerProvider(ApiFailureMapper.class);
        dispatcher.getProviderFactory().registerProvider(AuditRequestFilter.class);
        dispatcher.getProviderFactory().registerProvider(AuditResponseFilter.class);
        dispatcher.getProviderFactory().registerProvider(PlatformTenantDispatchTest.JsonWriter.class);
        dispatcher.getRegistry().addSingletonResource(new HospitalResource(auth,new PaymentService(manager),new PlatformTenantService(manager),new StatisticsService(manager,java.time.Clock.systemUTC()),service));
        dispatcher.getRegistry().addSingletonResource(new AuditResource(auth,service));
        dispatcher.getRegistry().addSingletonResource(new HospitalSlotResource());
        dispatcher.getRegistry().addSingletonResource(new PaymentResource());
        dispatcher.getRegistry().addSingletonResource(new PlatformTenantResource(auth,new PlatformTenantService(manager)));
        dispatcher.getRegistry().addSingletonResource(new StatisticsResource(auth,new StatisticsService(manager,java.time.Clock.systemUTC())));
        dispatcher.getRegistry().addSingletonResource(new TenantContextResource(auth,new PlatformTenantService(manager)));
    }
    @Test void anonymousBadPaginationIs401() throws Exception {
        for(String route:new String[]{"hospital","platform"}) {
            MockHttpResponse anonymousResponse=get("/"+route+"/audit-events?page=abc&size=2147483648",null);
            assertEquals(401,anonymousResponse.getStatus()); assertErrorId(anonymousResponse,"UNAUTHORIZED");
        }
    }
    @Test void wrongRoleBadFilterIs403() throws Exception {
        for(String user:new String[]{"patient","doctor","admin","platform"}) {
            String route=user.equals("platform")?"hospital":"platform";
            MockHttpResponse r=get("/"+route+"/audit-events?eventType=bad&tenantId=bad",bearer(user));
            assertEquals(403,r.getStatus()); assertErrorId(r,"FORBIDDEN");
        }
        for(String user:new String[]{"patient","doctor"}) assertEquals(403,get("/hospital/audit-events?page=bad",bearer(user)).getStatus());
    }
    @ParameterizedTest @ValueSource(strings={"tenantId=bad","tenantId=1-1-1-1-1","tenantId=30000000-0000-0000-0000-000000000000","tenantId=10000000-0000-0000-0000-000000000000&tenantId=20000000-0000-0000-0000-000000000000","page=2147483647&size=100","unknown=x","result=bad"})
    void adminBadOrDuplicateTenantFilterIs400(String raw) throws Exception {
        MockHttpResponse r=get("/platform/audit-events?"+raw,bearer("platform")); assertEquals(400,r.getStatus()); assertErrorId(r,"BAD_REQUEST");
    }
    @Test void hospitalRejectsTenantScopeAndDisabledAccountBeforeParsing() throws Exception {
        MockHttpResponse r=get("/hospital/audit-events?tenantId="+B,bearer("admin")); assertEquals(400,r.getStatus()); assertErrorId(r,"BAD_REQUEST");
        String token=bearer("admin"); update("update user_account set status='DISABLED' where id='admin'");
        MockHttpResponse disabled=get("/hospital/audit-events?page=bad",token); assertEquals(401,disabled.getStatus()); assertErrorId(disabled,"UNAUTHORIZED");
    }
    @Test void auditRoutesCoexistWithOriginalOrdersStatisticsContextAndTenants() throws Exception {
        for(String route:new String[]{"hospital","platform"}) {
            String authorization=bearer(route.equals("hospital")?"admin":"platform");
            manager.clear();
            org.hibernate.stat.Statistics sql=manager.unwrap(org.hibernate.Session.class).getSessionFactory().getStatistics();
            sql.setStatisticsEnabled(true); sql.clear();
            MockHttpResponse r=get("/"+route+"/audit-events?from=2026-10-01&to=2026-10-01",authorization);
            assertEquals(200,r.getStatus()); assertEquals(route.equals("hospital")?2:4,new ObjectMapper().readTree(r.getContentAsString()).path("data").path("total").asInt());
            UUID.fromString(r.getOutputHeaders().getFirst("X-Request-ID").toString());
            assertEquals(0,sql.getEntityLoadCount(),"the actual audit route must not load identity or clinical entities");
            assertEquals(0,sql.getEntityInsertCount()); assertEquals(0,sql.getEntityUpdateCount());
        }
        for(String route:new String[]{"/hospital/orders","/hospital/statistics?from=2026-10-01&to=2026-10-01","/hospital/context"}) assertEquals(200,get(route,bearer("admin")).getStatus(),route);
        assertEquals(200,get("/platform/tenants",bearer("platform")).getStatus());
        assertEquals(200,get("/platform/statistics?from=2026-10-01&to=2026-10-01",bearer("platform")).getStatus());
        assertEquals(200,get("/doctor/context",bearer("doctor")).getStatus());
    }
    @Test void disabledHospitalHistoryRemainsAccessibleThroughBothAuditRoutes() throws Exception {
        update("update tenant set status='DISABLED' where id=?1",A);
        assertEquals(200,get("/hospital/audit-events?from=2026-10-01&to=2026-10-01",bearer("admin")).getStatus());
        assertEquals(200,get("/platform/audit-events?from=2026-10-01&to=2026-10-01&tenantId="+A,bearer("platform")).getStatus());
    }
    @ParameterizedTest @ValueSource(strings={"Bearer invalid","Basic invalid","Bearer "})
    void invalidAuthenticationPrecedesBadFilter(String authorization) throws Exception {
        for(String route:new String[]{"hospital","platform"}) {
            MockHttpResponse r=get("/"+route+"/audit-events?result=invalid&page=abc",authorization);
            assertEquals(401,r.getStatus()); assertErrorId(r,"UNAUTHORIZED");
        }
    }
    MockHttpResponse get(String path,String authorization)throws Exception {
        long before=((Number)manager.createNativeQuery("select count(*) from audit_event").getSingleResult()).longValue();
        MockHttpRequest r=MockHttpRequest.get(path); r.header("X-Request-ID","00000000-0000-0000-0000-000000000099"); if(authorization!=null)r.header("Authorization",authorization);
        MockHttpResponse response=new MockHttpResponse(); dispatcher.invoke(r,response);
        assertEquals(before,((Number)manager.createNativeQuery("select count(*) from audit_event").getSingleResult()).longValue(),"GET must not append operation audit events");
        return response;
    }
    String bearer(String user){return "Bearer "+auth.login(new LoginRequest(user,PASSWORD)).getAccessToken();}
    void assertErrorId(MockHttpResponse r,String code)throws Exception {
        JsonNode error=new ObjectMapper().readTree(r.getContentAsString()); assertEquals(code,error.path("code").asText());
        String id=error.path("requestId").asText(); UUID.fromString(id); assertEquals(id,r.getOutputHeaders().getFirst("X-Request-ID"));
        assertNotEquals("00000000-0000-0000-0000-000000000099",id); assertFalse(error.has("data"));
    }
}
