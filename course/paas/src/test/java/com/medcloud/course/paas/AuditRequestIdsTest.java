package com.medcloud.course.paas;

import com.medcloud.course.paas.api.*;
import org.junit.jupiter.api.Test;
import javax.ws.rs.container.*;
import javax.ws.rs.core.*;
import javax.ws.rs.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.resteasy.mock.*;
import org.jboss.resteasy.core.Dispatcher;
import com.medcloud.course.paas.audit.AuditedOperationException;
import org.jboss.resteasy.spi.HttpRequest;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditRequestIdsTest {
    private ContainerRequestContext request(String spoof) {
        Map<String,Object> properties = new HashMap<>();
        return (ContainerRequestContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ContainerRequestContext.class}, (p,m,a) -> {
            if (m.getName().equals("getProperty")) return properties.get(a[0]);
            if (m.getName().equals("setProperty")) { properties.put((String)a[0],a[1]); return null; }
            if (m.getName().equals("getHeaderString")) return spoof;
            throw new UnsupportedOperationException(m.getName());
        });
    }
    @Test void spoofedHeaderIsIgnoredAndParallelContextsDoNotMix() throws Exception {
        String spoof="11111111-1111-4111-8111-111111111111";
        ExecutorService threads=Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> ids=new ArrayList<>();
            for(int i=0;i<32;i++) ids.add(threads.submit(() -> {ContainerRequestContext c=request(spoof); String id=AuditRequestIds.ensure(c); assertEquals(id,AuditRequestIds.ensure(c)); assertNotEquals(spoof,id); assertEquals(id, UUID.fromString(id).toString()); return id;}));
            Set<String> unique=new HashSet<>(); for(Future<String> id:ids) unique.add(id.get()); assertEquals(32,unique.size());
        } finally {threads.shutdownNow();}
    }
    @Test void boundFiltersUseOneServerIdForResponseHeader() throws Exception {
        ContainerRequestContext request=request("sensitive-sentinel");
        new AuditRequestFilter().filter(request);
        String id=AuditRequestIds.ensure(request);
        MultivaluedMap<String,Object> headers=new MultivaluedHashMap<>(); headers.putSingle("X-Request-ID","spoofed");
        ContainerResponseContext response=(ContainerResponseContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ContainerResponseContext.class},(p,m,a)->{if(m.getName().equals("getHeaders"))return headers; throw new UnsupportedOperationException(m.getName());});
        new AuditResponseFilter().filter(request,response);
        assertEquals(id,headers.getFirst("X-Request-ID"));
    }
    @Path("/audit-boundary-fixture")
    public static class BoundaryFixture {
        @Context private HttpRequest request;
        @GET @AuditTracked @Produces(MediaType.APPLICATION_JSON)
        public Object query() { throw new IllegalArgumentException("sensitive-sentinel"); }
        @POST @AuditTracked @Produces(MediaType.APPLICATION_JSON)
        public Object write() {
            throw new AuditedOperationException(new SecurityException("sensitive-sentinel"), AuditRequestIds.ensure(request));
        }
        @GET @Path("legacy") @Produces(MediaType.APPLICATION_JSON)
        public Object legacy() { throw new SecurityException("sensitive-sentinel"); }
    }
    @Test void dispatchedTrackedFailureSharesErrorAndFilterId() throws Exception {
        Dispatcher dispatcher = dispatcher();
        MockHttpRequest request = MockHttpRequest.get("/audit-boundary-fixture");
        request.header("X-Request-ID", "spoofed");
        MockHttpResponse response = new MockHttpResponse(); dispatcher.invoke(request,response);
        assertEquals(400,response.getStatus());
        String id = new ObjectMapper().readTree(response.getContentAsString()).path("requestId").asText();
        assertEquals(id,response.getOutputHeaders().getFirst("X-Request-ID")); assertNotEquals("spoofed",id);
        assertEquals(id, AuditRequestIds.get((HttpRequest) request));
    }
    @Test void dispatchedAuditedWrapperUsesSameServerPropertyAndHeader() throws Exception {
        MockHttpRequest request = MockHttpRequest.post("/audit-boundary-fixture"); request.header("X-Request-ID","spoofed");
        MockHttpResponse response = new MockHttpResponse(); dispatcher().invoke(request,response);
        assertEquals(401,response.getStatus());
        String id = new ObjectMapper().readTree(response.getContentAsString()).path("requestId").asText();
        assertEquals(id, AuditRequestIds.get((HttpRequest) request)); assertEquals(id, response.getOutputHeaders().getFirst("X-Request-ID"));
    }
    @Test void legacyErrorWithoutTrackedContextKeepsOriginalStatusAndSafeGeneratedId() throws Exception {
        MockHttpRequest request = MockHttpRequest.get("/audit-boundary-fixture/legacy"); request.header("X-Request-ID","spoofed");
        MockHttpResponse response = new MockHttpResponse(); dispatcher().invoke(request,response);
        assertEquals(401,response.getStatus());
        String id = new ObjectMapper().readTree(response.getContentAsString()).path("requestId").asText();
        assertEquals(id,UUID.fromString(id).toString()); assertNull(AuditRequestIds.get((HttpRequest) request));
        assertNull(response.getOutputHeaders().getFirst("X-Request-ID"));
    }
    private Dispatcher dispatcher() {
        Dispatcher dispatcher = MockDispatcherFactory.createDispatcher();
        dispatcher.getProviderFactory().registerProvider(ApiFailureMapper.class);
        dispatcher.getProviderFactory().registerProvider(AuditRequestFilter.class);
        dispatcher.getProviderFactory().registerProvider(AuditResponseFilter.class);
        dispatcher.getProviderFactory().registerProvider(PlatformTenantDispatchTest.JsonWriter.class);
        dispatcher.getRegistry().addPerRequestResource(BoundaryFixture.class);
        return dispatcher;
    }
}
