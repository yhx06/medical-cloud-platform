package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.*;
import com.medcloud.course.contract.Role;
import com.medcloud.course.portal.web.PaasClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import javax.servlet.http.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlatformAdministrationPortalTest {
    static final String KEY="12345678-1234-1234-1234-123456789abc", PASSWORD="fake-Original!9";
    static HttpServlet servlet(PaasClient client){return assertDoesNotThrow(()->(HttpServlet)Class.forName("com.medcloud.course.portal.web.PlatformAdministrationServlet").getConstructor(PaasClient.class).newInstance(client));}
    @Test void allRolesMissingSessionCsrfAndDuplicateFieldsBlockWrites()throws Exception{
        try(Fixture f=new Fixture()){
            for(Role role:Role.values())if(role!=Role.PLATFORM_ADMIN){MockHttpServletRequest r=f.post("/platform/hospital/open");r.getSession().setAttribute("role",role);assertEquals(403,f.call(r).getStatus());}
            MockHttpServletRequest r=f.post("/platform/hospital/open");r.setSession(null);assertEquals("/portal/login",f.call(r).getRedirectedUrl());
            for(String field:new String[]{"csrfToken","hospitalName","username","initialPassword","confirmInitialPassword","operationKey"}){r=f.post("/platform/hospital/open");r.addParameter(field,"duplicate");assertEquals(field.equals("csrfToken")?403:400,f.call(r).getStatus(),field);}
            r=f.post("/platform/hospital/open");r.removeParameter("csrfToken");assertEquals(403,f.call(r).getStatus());assertEquals(0,f.writes);
        }
    }
    @Test void confirmationAndPasswordRulesRejectBeforeHttp()throws Exception{
        try(Fixture f=new Fixture()){
            for(String password:new String[]{"1234567","a\nbcdefghi","abcdefg\uD800",PaasSafeErrorTest.repeat('a',73)}){MockHttpServletRequest r=f.post("/platform/hospital/open");r.setParameter("initialPassword",password);r.setParameter("confirmInitialPassword",password);assertEquals(400,f.call(r).getStatus());}
            MockHttpServletRequest r=f.post("/platform/hospital/open");r.setParameter("confirmInitialPassword","different");assertEquals(400,f.call(r).getStatus());assertEquals(0,f.writes);
        }
    }
    @Test void creationUsesSafePayloadAndIndependentSessionResultsWithRefreshAndEviction()throws Exception{
        try(Fixture f=new Fixture()){
            MockHttpServletRequest first=f.post("/platform/hospital/open");first.addParameter("role","DOCTOR");MockHttpSession session=(MockHttpSession)first.getSession();
            MockHttpServletResponse success=f.call(first);assertEquals(303,success.getStatus());String location=success.getHeader("Location");assertTrue(location.startsWith("/portal/platform/hospital/result?resultKey="));assertFalse(location.contains("Hospital"));
            JsonNode body=new ObjectMapper().readTree(f.body);assertEquals(4,body.size());assertFalse(body.has("confirmInitialPassword"));assertFalse(body.has("role"));assertEquals(PASSWORD,body.path("initialPassword").asText());
            String nonce=location.substring(location.indexOf('=')+1);
            for(int i=0;i<2;i++){MockHttpServletRequest r=f.get("/platform/hospital/result",session);r.addParameter("resultKey",nonce);r.addParameter("username","forged");f.call(r);assertNotNull(r.getAttribute("creationResult"));assertEquals("VerifiedUser",r.getAttribute("resultUsername"));}assertEquals(1,f.writes);
            MockHttpServletRequest other=f.get("/platform/hospital/result",new MockHttpSession());other.getSession().setAttribute("role",Role.PLATFORM_ADMIN);other.getSession().setAttribute("accessToken","fake");other.addParameter("resultKey",nonce);f.call(other);assertNull(other.getAttribute("creationResult"));
            for(int i=0;i<10;i++){MockHttpServletRequest r=f.post("/platform/hospital/open");r.setSession(session);r.setParameter("operationKey",UUID.randomUUID().toString());assertEquals(303,f.call(r).getStatus());if(i==0){MockHttpServletRequest original=f.get("/platform/hospital/result",session);original.addParameter("resultKey",nonce);f.call(original);assertNotNull(original.getAttribute("creationResult"));}}
            MockHttpServletRequest expired=f.get("/platform/hospital/result",session);expired.addParameter("resultKey",nonce);f.call(expired);assertNull(expired.getAttribute("creationResult"));
            for(Object value:Collections.list(session.getAttributeNames()))assertFalse(String.valueOf(session.getAttribute(String.valueOf(value))).contains(PASSWORD));
        }
    }
    @Test void failedOrLostResponseRetainsNonSecretsAndSameKeyWithoutAutomaticRetry()throws Exception{
        try(Fixture f=new Fixture()){
            for(int status:new int[]{409,503}){f.status=status;MockHttpServletRequest r=f.post("/platform/hospital/open");int before=f.writes;MockHttpServletResponse s=f.call(r);assertEquals(status,s.getStatus());assertNull(s.getHeader("Location"));assertEquals(KEY,r.getAttribute("operationKey"));assertEquals("Hospital <demo>",r.getAttribute("hospitalName"));assertNull(r.getAttribute("initialPassword"));assertNull(r.getAttribute("confirmInitialPassword"));assertNull(r.getSession().getAttribute("flash"));assertEquals(before+1,f.writes);}
            f.status=200;MockHttpServletRequest retry=f.post("/platform/hospital/open");assertEquals(303,f.call(retry).getStatus());JsonNode body=new ObjectMapper().readTree(f.body);assertEquals(KEY,body.path("operationKey").asText());assertEquals(PASSWORD,body.path("initialPassword").asText());
        }
    }
    @Test void legacyIdsAndPagingEncodedAndStaleStatusNeverOverwritten()throws Exception{
        try(Fixture f=new Fixture()){
            MockHttpServletRequest r=f.get("/platform/hospital/administrators",null);r.addParameter("tenantId","legacy +/#");r.addParameter("page","2");r.addParameter("size","5");assertEquals(200,f.call(r).getStatus());assertNotNull(r.getAttribute("administrators"));assertEquals("GET /platform/tenants/legacy%20%2B%2F%23/administrators?page=2&size=5",f.last);
            r=f.post("/platform/hospital/administrator/create");r.setParameter("tenantId","tenant-a");assertEquals(303,f.call(r).getStatus());assertTrue(f.last.contains("/tenant-a/administrators"));assertEquals(3,new ObjectMapper().readTree(f.body).size());
            f.status=409;r=f.post("/platform/hospital/administrator/status");r.setParameter("tenantId","tenant-a");r.setParameter("adminId","admin +");r.setParameter("status","DISABLED");r.setParameter("expectedStatus","ACTIVE");assertEquals(409,f.call(r).getStatus());assertEquals("ACTIVE",r.getAttribute("expectedStatus"));assertEquals("PUT /platform/tenants/tenant-a/administrators/admin%20%2B/status",f.last);assertEquals(2,new ObjectMapper().readTree(f.body).size());
        }
    }
    @Test void resultRevalidatesBackendIdentityBeforeReadingNonce()throws Exception{
        try(Fixture f=new Fixture()){
            MockHttpServletRequest r=f.post("/platform/hospital/open");String location=f.call(r).getHeader("Location");MockHttpSession old=(MockHttpSession)r.getSession();f.status=401;MockHttpServletRequest result=f.get("/platform/hospital/result",old);result.addParameter("resultKey",location.substring(location.indexOf('=')+1));assertEquals("/portal/login",f.call(result).getRedirectedUrl());assertNull(result.getAttribute("creationResult"));assertTrue(old.isInvalid());
        }
    }
    @Test void lostCreationResponseNeverAutomaticallyRepostsAndManualRetryKeepsOriginalKeyAndPassword()throws Exception{
        try(Fixture f=new Fixture()){
            f.dropNext=true;MockHttpServletRequest first=f.post("/platform/hospital/open");MockHttpServletResponse lost=f.call(first);
            assertEquals(503,lost.getStatus());assertEquals(1,f.writes,"a lost response must never trigger an automatic business POST");assertEquals(KEY,first.getAttribute("operationKey"));
            MockHttpServletRequest retry=f.post("/platform/hospital/open");retry.setSession((MockHttpSession)first.getSession());assertEquals(303,f.call(retry).getStatus());
            JsonNode body=new ObjectMapper().readTree(f.body);assertEquals(KEY,body.path("operationKey").asText());assertEquals(PASSWORD,body.path("initialPassword").asText());assertEquals(2,f.writes);
        }
    }
    static final class Fixture implements AutoCloseable{
        HttpServer server;HttpServlet servlet;int status=200,writes;String body,last;boolean dropNext;
        Fixture()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api",x->{
                last=x.getRequestMethod()+" "+x.getRequestURI().toASCIIString().substring(4);
                body=new String(PortalFixture.read(x.getRequestBody()),StandardCharsets.UTF_8);
                if(!x.getRequestMethod().equals("GET"))writes++;
                if(dropNext){dropNext=false;x.close();return;}
                String rawPath=x.getRequestURI().getRawPath(),tenant="tenant-a";
                if(rawPath.contains("/administrators"))tenant=java.net.URLDecoder.decode(rawPath.substring(rawPath.indexOf("/tenants/")+9,rawPath.indexOf("/administrators")),"UTF-8");
                String data=x.getRequestMethod().equals("GET")?"{\"tenant\":{\"id\":"+new ObjectMapper().writeValueAsString(tenant)+",\"name\":\"VerifiedHospital\",\"status\":\"ACTIVE\"},\"items\":[],\"total\":0,\"page\":2,\"size\":5,\"activeAdministratorCount\":1}":"{\"tenantId\":\"tenant-a\",\"hospitalName\":\"VerifiedHospital\",\"administratorId\":\"admin-a\",\"username\":\"VerifiedUser\"}";
                String reply=status==200?"{\"code\":\"OK\",\"data\":"+data+"}":"{\"code\":\"LAST_ACTIVE_HOSPITAL_ADMIN\",\"message\":\""+PASSWORD+"\"}";
                byte[] b=reply.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);x.close();
            });server.start();
            try{servlet=servlet(new PaasClient("http://127.0.0.1:"+server.getAddress().getPort()+"/api"));}catch(AssertionError failure){server.stop(0);throw failure;}
        }
        MockHttpServletRequest get(String path,MockHttpSession existing){MockHttpServletRequest r=new MockHttpServletRequest("GET","/portal"+path);r.setContextPath("/portal");r.setServletPath(path);MockHttpSession s=existing==null?new MockHttpSession():existing;if(existing==null){s.setAttribute("role",Role.PLATFORM_ADMIN);s.setAttribute("csrfToken","csrf");s.setAttribute("accessToken","fake");}r.setSession(s);return r;}
        MockHttpServletRequest post(String path){MockHttpServletRequest r=get(path,null);r.setMethod("POST");r.setParameter("csrfToken","csrf");r.setParameter("hospitalName","Hospital <demo>");r.setParameter("username","NewUser");r.setParameter("initialPassword",PASSWORD);r.setParameter("confirmInitialPassword",PASSWORD);r.setParameter("operationKey",KEY);return r;}
        MockHttpServletResponse call(MockHttpServletRequest r)throws Exception{MockHttpServletResponse s=new MockHttpServletResponse();servlet.service(r,s);return s;}
        public void close(){server.stop(0);}
    }
}
