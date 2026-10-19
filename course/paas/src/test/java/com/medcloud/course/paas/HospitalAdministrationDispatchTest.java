package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.*;
import org.jboss.resteasy.core.Dispatcher;
import org.jboss.resteasy.mock.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HospitalAdministrationDispatchTest extends HospitalAdministrationDispatchFixture {
    // Missing or wrongly registered routes fail these assertions through real RESTEasy dispatch.
    @Test void allFourRoutesReturnContractDtosWithoutPasswordFields() throws Exception {
        JsonNode opened = data(invoke("POST", "/platform/tenants", "platform_1", openingJson("New_Admin")));
        assertEquals("虚构新医院", opened.path("hospitalName").asText());
        assertEquals("New_Admin", opened.path("username").asText());
        assertEquals(new HashSet<>(Arrays.asList("tenantId","hospitalName","administratorId","username")), fields(opened));
        JsonNode added = data(invoke("POST", "/platform/tenants/tenant-a/administrators", "platform_1", creationJson("extra_admin")));
        assertEquals("tenant-a", added.path("tenantId").asText());
        JsonNode page = data(invoke("GET", "/platform/tenants/tenant-a/administrators", "platform_1", null));
        assertEquals(2, page.path("total").asInt()); assertEquals(2, page.path("activeAdministratorCount").asInt());
        assertEquals(1, page.path("page").asInt()); assertEquals(20, page.path("size").asInt());
        assertEquals(new HashSet<>(Arrays.asList("tenant","items","total","page","size","activeAdministratorCount")), fields(page));
        assertEquals(new HashSet<>(Arrays.asList("id","tenantId","username","status")), fields(page.path("items").get(0)));
        JsonNode changed = data(invoke("PUT", "/platform/tenants/tenant-a/administrators/admin-a/status", "platform_1", statusJson("DISABLED","ACTIVE")));
        assertEquals("DISABLED", changed.path("status").asText());
        assertEquals(3, writeCalls);
    }

    // Moving role/auth validation after pagination or business calls violates precedence and write isolation.
    @Test void fourRolesAndAnonymousAreCheckedBeforeBindableInvalidInput() throws Exception {
        for (String username : Arrays.asList(null,"admin_a","doctor_a","patient_a")) {
            int status = username == null ? 401 : 403;
            String code = username == null ? "UNAUTHORIZED" : "FORBIDDEN";
            error(invoke("POST","/platform/tenants",username,"{}"),status,code);
            error(invoke("POST","/platform/tenants/tenant-a/administrators",username,"{}"),status,code);
            error(invoke("PUT","/platform/tenants/tenant-a/administrators/admin-a/status",username,"{}"),status,code);
            error(invoke("GET","/platform/tenants/tenant-a/administrators?page=bad&size=101",username,null),status,code);
        }
        assertEquals(0,writeCalls); assertEquals(0,listCalls);
        assertEquals(2,count(Tenant.class)); assertEquals(0,count(HospitalAdminOperation.class));
    }

    @Test void authorizedInvalidPaginationRejectsOverflowAndSize101() throws Exception {
        for(String query : Arrays.asList("page=bad","size=bad","page=2147483648","size=2147483648","page=0","size=0","size=101","page=2147483647&size=100"))
            error(invoke("GET","/platform/tenants/tenant-a/administrators?"+query,"platform_1",null),400,"HOSPITAL_ONBOARDING_INVALID");
        assertEquals(0,listCalls);
    }

    @Test void opaqueIdsArePassedToServiceAndMissingOrForeignTargetsAreForbidden() throws Exception {
        // Seed non-UUID IDs already used by the legacy course schema; never coerce to UUID/numeric IDs.
        assertEquals("tenant-a",data(invoke("GET","/platform/tenants/tenant-a/administrators","platform_1",null)).path("tenant").path("id").asText());
        error(invoke("GET","/platform/tenants/missing/administrators","platform_1",null),403,"FORBIDDEN");
        error(invoke("POST","/platform/tenants/missing/administrators","platform_1",creationJson("new_admin")),403,"FORBIDDEN");
        error(invoke("PUT","/platform/tenants/tenant-a/administrators/admin-b/status","platform_1",statusJson("DISABLED","ACTIVE")),403,"FORBIDDEN");
    }

    @Test void realProviderRejectsMalformedJsonAndUnknownRoleBeforeBusinessWrites() throws Exception {
        for(String path : Arrays.asList("/platform/tenants","/platform/tenants/tenant-a/administrators")) {
            MockHttpResponse malformed = invoke("POST",path,"platform_1","{\"initialPassword\":\"private-malformed-sentinel\",");
            assertEquals(400,malformed.getStatus()); assertFalse(text(malformed).contains("private-malformed-sentinel"));
            MockHttpResponse role = invoke("POST",path,"platform_1","{\"role\":\"SUPERUSER\"}");
            assertEquals(400,role.getStatus());
        }
        assertEquals(0,writeCalls); assertEquals(0,count(HospitalAdminOperation.class));
    }

    @Test void wrongMediaTypeReturns415WithoutBusinessWrites() throws Exception {
        MockHttpResponse response = invoke("POST","/platform/tenants","platform_1",openingJson("new_admin"),"text/plain");
        assertEquals(415,response.getStatus()); assertFalse(text(response).contains(PASSWORD)); assertEquals(0,writeCalls);
    }

    @Test void frozenHospitalCanAddAndEnableWithoutThawingAndDisabledPlatformCannotWrite() throws Exception {
        inTx(em -> {em.find(Tenant.class,"tenant-a").setStatus("DISABLED");em.find(Account.class,"admin-a").disable();return null;});
        data(invoke("POST","/platform/tenants/tenant-a/administrators","platform_1",creationJson("repair_admin")));
        assertEquals("ACTIVE",data(invoke("PUT","/platform/tenants/tenant-a/administrators/admin-a/status","platform_1",statusJson("ACTIVE","DISABLED"))).path("status").asText());
        assertEquals("DISABLED",this.<String>inTx(em -> em.find(Tenant.class,"tenant-a").getStatus()));
        String bearer = bearer("platform_1");
        inTx(em -> {em.find(Account.class,"platform-1").disable();return null;});
        error(invokeAuthorized("POST","/platform/tenants",bearer,"{}","application/json"),401,"UNAUTHORIZED");
        assertEquals(2,writeCalls);
    }
}

/** Real provider and business persistence; resource-local transactions stand in for separate EJB proxies. */
abstract class HospitalAdministrationDispatchFixture extends HospitalAdministrationFixture {
    static final String SECRET = "fictional-onboarding-test-secret-123456789";
    static final String CLIENT_ID = "00000000-0000-0000-0000-000000000099";
    final ObjectMapper json = new ObjectMapper();
    Dispatcher dispatcher;
    AuthService auth;
    URLClassLoader providerLoader;
    int writeCalls, listCalls;
    boolean insideRunner;
    boolean mutationRequest;
    RuntimeException injectedFailure;

    @BeforeEach void dispatchFixture() throws Exception {
        auth = new AuthService() {
            @Override public CurrentActor authenticate(String token) {
                if(mutationRequest)assertTrue(insideRunner,"authentication EJB must be inside nontransactional audit runner");
                return inTx(em -> new AuthService(em,SECRET).authenticate(token));
            }
        };
        AuditFailureJournal failures = new AuditFailureJournal() {
            @Override public void append(AuditContext c,AuditReasonCode reason,Instant at) {
                inTx(em -> {new AuditFailureJournal(em).append(c,reason,at);return null;});
            }
        };
        AuditOperationRunner runner = new AuditOperationRunner(failures) {
            @Override public <T> T execute(AuditContext c,AuditAction<T> action) {
                assertFalse(insideRunner); insideRunner=true;
                try {return super.execute(c,action);} finally {insideRunner=false;}
            }
        };
        PlatformAdministrationService administration = new PlatformAdministrationService() {
            private void writing() { assertTrue(insideRunner,"business EJB must be inside audit runner");writeCalls++;if(injectedFailure!=null)throw injectedFailure; }
            @Override public HospitalAdminCreationView openHospital(CurrentActor a,HospitalOpenRequest r,AuditContext c) {
                writing();return inTx(em -> new PlatformAdministrationService(em).openHospital(a,r,c));
            }
            @Override public HospitalAdminCreationView addAdministrator(CurrentActor a,String id,HospitalAdministratorCreateRequest r,AuditContext c) {
                writing();return inTx(em -> new PlatformAdministrationService(em).addAdministrator(a,id,r,c));
            }
            @Override public HospitalAdministratorView changeAdministratorStatus(CurrentActor a,String id,String admin,TenantStatusRequest r,AuditContext c) {
                writing();return inTx(em -> new PlatformAdministrationService(em).changeAdministratorStatus(a,id,admin,r,c));
            }
            @Override public HospitalAdministratorPage administrators(CurrentActor a,String id,int p,int s) {
                listCalls++;return inTx(em -> new PlatformAdministrationService(em).administrators(a,id,p,s));
            }
        };
        dispatcher=MockDispatcherFactory.createDispatcher();
        dispatcher.getProviderFactory().registerProvider(ApiFailureMapper.class);
        dispatcher.getProviderFactory().registerProvider(AuditRequestFilter.class);
        dispatcher.getProviderFactory().registerProvider(AuditResponseFilter.class);
        registerContainerJsonProvider();
        PlatformTenantService tenants = new PlatformTenantService();
        try {
            dispatcher.getRegistry().addSingletonResource(PlatformTenantResource.class.getConstructor(AuthService.class,PlatformTenantService.class,PlatformAdministrationService.class,AuditOperationRunner.class).newInstance(auth,tenants,administration,runner));
        } catch(NoSuchMethodException missing) {dispatcher.getRegistry().addSingletonResource(new PlatformTenantResource(auth,tenants,runner));}
        try {
            Class<?> resource=Class.forName("com.medcloud.course.paas.api.HospitalAdministratorResource");
            dispatcher.getRegistry().addSingletonResource(resource.getConstructor(AuthService.class,PlatformAdministrationService.class,AuditOperationRunner.class).newInstance(auth,administration,runner));
        } catch(ClassNotFoundException missing) { /* Missing routes must fail through HTTP dispatch in RED. */ }
    }

    void registerContainerJsonProvider() throws Exception {
        String name="org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider";
        Class<?> provider;
        try {provider=Class.forName(name);} catch(ClassNotFoundException missing) {
            String home=System.getProperty("course.test.wildfly.home");
            assertNotNull(home,"Teacher WildFly JSON provider is required; no substitute reader/writer");
            Path base=Paths.get(home,"modules","system","layers","base");List<URL> jars=new ArrayList<>();
            for(String directory:Arrays.asList("org/jboss/resteasy/resteasy-jackson2-provider","com/fasterxml/jackson")) {
                Path root=base.resolve(directory);
                if(Files.exists(root))try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                    paths.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {try {jars.add(p.toUri().toURL());}catch(MalformedURLException bad){throw new IllegalArgumentException(bad);}});
                }
            }
            providerLoader=new URLClassLoader(jars.toArray(new URL[0]),getClass().getClassLoader()) {
                @Override protected synchronized Class<?> loadClass(String n,boolean resolve)throws ClassNotFoundException {
                    if(n.startsWith("com.fasterxml.jackson.")||n.startsWith("org.jboss.resteasy.plugins.providers.jackson.")) {
                        Class<?> loaded=findLoadedClass(n);if(loaded==null)loaded=findClass(n);if(resolve)resolveClass(loaded);return loaded;
                    }
                    return super.loadClass(n,resolve);
                }
            };
            provider=Class.forName(name,true,providerLoader);
        }
        assertTrue(provider.getName().startsWith("org.jboss.resteasy.plugins.providers.jackson."));
        dispatcher.getProviderFactory().registerProvider(provider);
    }
    @AfterEach void closeProvider() throws Exception {if(providerLoader!=null)providerLoader.close();}
    String bearer(String username) {return "Bearer "+inTx(em -> new AuthService(em,SECRET).login(new LoginRequest(username,PASSWORD))).getAccessToken();}
    MockHttpResponse invoke(String method,String path,String username,String body)throws Exception {return invoke(method,path,username,body,"application/json");}
    MockHttpResponse invoke(String method,String path,String username,String body,String media)throws Exception {return invokeAuthorized(method,path,username==null?null:bearer(username),body,media);}
    MockHttpResponse invokeAuthorized(String method,String path,String authorization,String body,String media)throws Exception {
        mutationRequest=!"GET".equals(method);
        MockHttpRequest request="GET".equals(method)?MockHttpRequest.get(path):"PUT".equals(method)?MockHttpRequest.put(path):MockHttpRequest.post(path);
        if(authorization!=null)request.header("Authorization",authorization);
        request.header("X-Request-ID",CLIENT_ID);
        if(body!=null){request.header("Content-Type",media);request.content(body.getBytes(StandardCharsets.UTF_8));}
        MockHttpResponse response=new MockHttpResponse();dispatcher.invoke(request,response);return response;
    }
    static String text(MockHttpResponse response){return new String(response.getOutput(),StandardCharsets.UTF_8);}
    JsonNode data(MockHttpResponse response)throws Exception {
        assertEquals(200,response.getStatus(),text(response));JsonNode body=json.readTree(response.getOutput());assertEquals("OK",body.path("code").asText());
        assertFalse(text(response).contains(PASSWORD));assertFalse(text(response).toLowerCase(Locale.ROOT).contains("password"));assertFalse(text(response).contains("$2a$"));return body.path("data");
    }
    JsonNode error(MockHttpResponse response,int status,String code)throws Exception {
        assertEquals(status,response.getStatus(),text(response));JsonNode body=json.readTree(response.getOutput());assertEquals(code,body.path("code").asText());
        String id=body.path("requestId").asText();UUID.fromString(id);assertNotEquals(CLIENT_ID,id);
        assertFalse(body.has("data"));assertFalse(text(response).contains(PASSWORD));
        Object header=response.getOutputHeaders().getFirst("X-Request-ID");if(header!=null)assertEquals(id,header.toString());return body;
    }
    String openingJson(String username)throws Exception {return json.writeValueAsString(new HospitalOpenRequest("虚构新医院",username,PASSWORD,UUID.randomUUID().toString()));}
    String creationJson(String username)throws Exception {return json.writeValueAsString(new HospitalAdministratorCreateRequest(username,PASSWORD,UUID.randomUUID().toString()));}
    String statusJson(String status,String expected)throws Exception {return json.writeValueAsString(new TenantStatusRequest(status,expected));}
    List<AuditEvent> events(AuditEventType type){return inTx(em -> em.createQuery("select e from AuditEvent e where e.eventType=:type order by e.recordedAt,e.id",AuditEvent.class).setParameter("type",type).getResultList());}
    Set<String> fields(JsonNode value){Set<String> names=new HashSet<>();value.fieldNames().forEachRemaining(names::add);return names;}
}
