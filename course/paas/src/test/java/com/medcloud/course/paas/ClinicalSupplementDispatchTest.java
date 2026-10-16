package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.consultation.*;
import org.jboss.resteasy.core.Dispatcher;
import org.jboss.resteasy.mock.*;
import org.junit.jupiter.api.*;
import javax.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Real routes, real service/DB behavior, and the container's unconfigured JSON provider. */
class ClinicalSupplementDispatchTest extends TenantFixture {
    Dispatcher dispatcher;
    AuthService auth;
    java.net.URLClassLoader providerLoader;
    final ObjectMapper assertions = new ObjectMapper();

    <T> T transaction(Function<EntityManager,T> work) {
        EntityManager em = factory.createEntityManager();
        try {
            em.getTransaction().begin(); T result = work.apply(em); em.getTransaction().commit(); return result;
        } finally { if (em.getTransaction().isActive()) em.getTransaction().rollback(); em.close(); }
    }

    @BeforeEach void dispatchFixture() throws Exception {
        auth = new AuthService(manager, SECRET);
        dispatcher = MockDispatcherFactory.createDispatcher();
        dispatcher.getProviderFactory().registerProvider(ApiFailureMapper.class);
        dispatcher.getProviderFactory().registerProvider(AuditRequestFilter.class);
        dispatcher.getProviderFactory().registerProvider(AuditResponseFilter.class);
        registerContainerJsonProvider();
        AuditOperationRunner runner = AuditLocalTransactions.runner(manager);
        ClinicalService clinical = new ClinicalService() {
            @Override public ClinicalRecord complete(CurrentActor a,String id,String c,String d,String v,String n,Instant now,AuditContext ctx) {
                return transaction(em -> new ClinicalService(em).complete(a,id,c,d,v,n,now,ctx));
            }
            @Override public ClinicalRecord complete(CurrentActor a,String id,String c,String d,String v,String n,Instant now,AuditContext ctx,Long revision) {
                return transaction(em -> new ClinicalService(em).complete(a,id,c,d,v,n,now,ctx,revision));
            }
        };
        dispatcher.getRegistry().addSingletonResource(new ClinicalResource(auth, clinical, runner));
        ClinicalSupplementService supplements = new ClinicalSupplementService() {
            @Override public PatientComplaintView complaint(CurrentActor a,String id) { return transaction(em -> new ClinicalSupplementService(em).complaint(a,id)); }
            @Override public PatientComplaintView updateComplaint(CurrentActor a,String id,SupplementComplaintRequest r,AuditContext c) { return transaction(em -> new ClinicalSupplementService(em).updateComplaint(a,id,r,c)); }
            @Override public ClinicalWorkspaceView workspace(CurrentActor a,String id) { return transaction(em -> new ClinicalSupplementService(em).workspace(a,id)); }
            @Override public ClinicalDraftView saveDraft(CurrentActor a,String id,SaveClinicalDraftRequest r,AuditContext c) { return transaction(em -> new ClinicalSupplementService(em).saveDraft(a,id,r,c)); }
            @Override public ClinicalRecordDetailsView doctorDetails(CurrentActor a,String id) { return transaction(em -> new ClinicalSupplementService(em).doctorDetails(a,id)); }
            @Override public ClinicalRecordDetailsView patientDetails(CurrentActor a,String id) { return transaction(em -> new ClinicalSupplementService(em).patientDetails(a,id)); }
            @Override public RecordCorrectionPage correctionsForDoctor(CurrentActor a,String id,String p,String s) { return transaction(em -> new ClinicalSupplementService(em).correctionsForDoctor(a,id,p,s)); }
            @Override public RecordCorrectionPage correctionsForPatient(CurrentActor a,String id,String p,String s) { return transaction(em -> new ClinicalSupplementService(em).correctionsForPatient(a,id,p,s)); }
            @Override public RecordCorrectionView appendCorrection(CurrentActor a,String id,AppendCorrectionRequest r,AuditContext c) { return transaction(em -> new ClinicalSupplementService(em).appendCorrection(a,id,r,c)); }
        };
        // Missing resource is an observable 404 in the pre-implementation RED, never a fake service.
        try {
            Class<?> resource = Class.forName("com.medcloud.course.paas.api.ClinicalSupplementResource");
            dispatcher.getRegistry().addSingletonResource(resource.getConstructor(AuthService.class,ClinicalSupplementService.class,AuditOperationRunner.class).newInstance(auth,supplements,runner));
        } catch (ClassNotFoundException notImplemented) { }
    }

    void registerContainerJsonProvider() throws Exception {
        String name = "org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider";
        Class<?> provider;
        try { provider = Class.forName(name); }
        catch (ClassNotFoundException missing) {
            String home = System.getProperty("course.test.wildfly.home");
            assertNotNull(home,"Supply course.test.wildfly.home for the real container JSON provider; no substitute reader is allowed");
            Path base = Paths.get(home,"modules","system","layers","base");
            List<URL> jars = new ArrayList<>();
            for (String directory : Arrays.asList("org/jboss/resteasy/resteasy-jackson2-provider", "com/fasterxml/jackson")) {
                Path root = base.resolve(directory);
                if (Files.exists(root)) try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                    paths.filter(p -> p.toString().endsWith(".jar")).forEach(p -> { try { jars.add(p.toUri().toURL()); } catch (MalformedURLException bad) { throw new IllegalArgumentException(bad); } });
                }
            }
            // Keep the teacher container's Jackson modules together; assertion-only Jackson stays separate.
            providerLoader = new URLClassLoader(jars.toArray(new URL[0]),getClass().getClassLoader()) {
                @Override protected synchronized Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                    if(name.startsWith("com.fasterxml.jackson.")||name.startsWith("org.jboss.resteasy.plugins.providers.jackson.")) {
                        Class<?> loaded=findLoadedClass(name);if(loaded==null)loaded=findClass(name);if(resolve)resolveClass(loaded);return loaded;
                    }
                    return super.loadClass(name,resolve);
                }
            };
            provider = Class.forName(name,true,providerLoader);
        }
        dispatcher.getProviderFactory().registerProvider(provider);
    }
    @AfterEach void closeProvider() throws Exception { if(providerLoader!=null) providerLoader.close(); }

    String bearer(String username) { return "Bearer " + AuditLocalTransactions.login(manager,auth,new LoginRequest(username,PASSWORD)).getAccessToken(); }
    MockHttpResponse invoke(String method,String path,String username,String body) throws Exception {
        MockHttpRequest request = "GET".equals(method) ? MockHttpRequest.get(path) : "PUT".equals(method) ? MockHttpRequest.put(path) : MockHttpRequest.post(path);
        if(username!=null) request.header("Authorization",bearer(username));
        request.header("X-Request-ID","00000000-0000-0000-0000-000000000099");
        if(body!=null) { request.header("Content-Type","application/json");request.content(body.getBytes(StandardCharsets.UTF_8)); }
        MockHttpResponse response = new MockHttpResponse(); dispatcher.invoke(request,response); manager.clear(); return response;
    }
    JsonNode data(MockHttpResponse response) throws Exception {
        assertEquals(200,response.getStatus(),new String(response.getOutput(),StandardCharsets.UTF_8)); return assertions.readTree(response.getOutput()).path("data");
    }
    JsonNode error(MockHttpResponse response,int status,String code) throws Exception {
        assertEquals(status,response.getStatus(),new String(response.getOutput(),StandardCharsets.UTF_8));
        JsonNode value=assertions.readTree(response.getOutput()); assertEquals(code,value.path("code").asText());
        String id=value.path("requestId").asText(); UUID.fromString(id); assertNotEquals("00000000-0000-0000-0000-000000000099",id);
        Object header=response.getOutputHeaders().getFirst("X-Request-ID");if(header!=null)assertEquals(id,header.toString());
        assertFalse(value.has("data"));return value;
    }
    String content(String c,String d,String a,String n) throws Exception {
        Map<String,Object> map=new LinkedHashMap<>();map.put("complaint",c);map.put("diagnosis",d);map.put("advice",a);map.put("notes",n);return assertions.writeValueAsString(map);
    }
    String correction(long revision,String key,String after,String reason) throws Exception {
        return "{\"expectedRevision\":"+revision+",\"operationKey\":\""+key+"\",\"after\":"+after+",\"reason\":"+assertions.writeValueAsString(reason)+"}";
    }
    List<AuditEvent> events(AuditEventType type) { return manager.createQuery("select e from AuditEvent e where e.eventType=:type",AuditEvent.class).setParameter("type",type).getResultList(); }

    @Test void anonymousBeforeInvalidPage401() throws Exception {
        error(invoke("GET","/patient/appointments/unpaid/record/corrections?page=bad&pageSize=6",null,null),401,"UNAUTHORIZED");
        error(invoke("GET","/doctor/records/record-unpaid/corrections?page=bad",null,null),401,"UNAUTHORIZED");
    }
    @Test void foreignParentDeniedBeforePagination() throws Exception {
        error(invoke("GET","/patient/appointments/unpaid/record/corrections?page=bad&pageSize=6","patient_b",null),403,"FORBIDDEN");
        error(invoke("GET","/doctor/records/record-unpaid/corrections?page=bad","doctor_b",null),403,"FORBIDDEN");
    }
    @Test void administratorCannotReadDraftOrCorrections() throws Exception {
        for(String username:Arrays.asList("admin_a","platform","patient_a")) {
            error(invoke("GET","/doctor/appointments/progress/clinical-workspace",username,null),403,"FORBIDDEN");
            error(invoke("GET","/doctor/records/record-unpaid/corrections",username,null),403,"FORBIDDEN");
        }
        error(invoke("GET","/patient/appointments/unpaid/record/details","admin_a",null),403,"FORBIDDEN");
    }
    @Test void crossPatientAndUnassignedDoctor403() throws Exception {
        error(invoke("GET","/patient/appointments/booked/complaint","patient_b",null),403,"FORBIDDEN");
        error(invoke("GET","/patient/appointments/unpaid/record/details","patient_b",null),403,"FORBIDDEN");
        error(invoke("GET","/doctor/records/record-unpaid","doctor_b",null),403,"FORBIDDEN");
        error(invoke("GET","/doctor/appointments/progress/clinical-workspace","doctor_b",null),403,"FORBIDDEN");
        error(invoke("PUT","/doctor/appointments/progress/draft","doctor_b","{\"expectedRevision\":0}"),403,"FORBIDDEN");
    }
    @Test void complaintAndDraftRoutesPersistAndPatientsNeverSeeDraft() throws Exception {
        assertEquals(0,data(invoke("GET","/patient/appointments/booked/complaint","patient_a",null)).path("revision").asInt());
        JsonNode complaint=data(invoke("PUT","/patient/appointments/booked/complaint","patient_a","{\"description\":\"虚构主诉\",\"expectedRevision\":0}"));
        assertEquals("虚构主诉",complaint.path("description").asText());assertEquals(1,complaint.path("revision").asInt());
        JsonNode draft=data(invoke("PUT","/doctor/appointments/progress/draft","doctor_a","{\"complaint\":\"draft private\",\"expectedRevision\":0}"));
        assertEquals(1,draft.path("revision").asInt());
        JsonNode workspace=data(invoke("GET","/doctor/appointments/progress/clinical-workspace","doctor_a",null));
        assertEquals("draft private",workspace.path("draft").path("content").path("complaint").asText());
        assertFalse(data(invoke("GET","/patient/appointments/progress/complaint","patient_a",null)).toString().contains("draft private"));
    }
    @Test void oldCompleteNoDraftCompatibility() throws Exception {
        JsonNode record=data(invoke("POST","/doctor/appointments/progress/complete","doctor_a",content("complaint","final diagnosis","final advice",null)));
        assertEquals("final diagnosis",record.path("diagnosis").asText());
        assertEquals(1,events(AuditEventType.CONSULTATION_COMPLETE).size());
    }
    @Test void withDraftMissingRevision409AndMatchingRevisionUsesFullSubmission() throws Exception {
        data(invoke("PUT","/doctor/appointments/progress/draft","doctor_a","{\"complaint\":\"private draft\",\"expectedRevision\":0}"));
        error(invoke("POST","/doctor/appointments/progress/complete","doctor_a",content(null,"submitted diagnosis","submitted advice",null)),409,"CLINICAL_REVISION_CONFLICT");
        assertEquals(AuditReasonCode.CONFLICT,events(AuditEventType.CONSULTATION_COMPLETE).get(0).getReasonCode());
        String submitted=content(null,"submitted diagnosis","submitted advice",null);
        JsonNode record=data(invoke("POST","/doctor/appointments/progress/complete","doctor_a",submitted.substring(0,submitted.length()-1)+",\"expectedDraftRevision\":1}"));
        assertTrue(record.path("complaint").isNull());assertEquals("submitted diagnosis",record.path("diagnosis").asText());
        assertEquals(0L,manager.createQuery("select count(d) from ClinicalDraft d",Long.class).getSingleResult());
    }
    @Test void repeatedCorrectionOneRowAndNoChangeAudit() throws Exception {
        String body=correction(0,UUID.randomUUID().toString(),content("complaint","amended diagnosis","advice","notes"),"private reason sentinel");
        try(AuditLogCapture logs=new AuditLogCapture()) {
            JsonNode first=data(invoke("POST","/doctor/records/record-unpaid/corrections","doctor_a",body));
            JsonNode retry=data(invoke("POST","/doctor/records/record-unpaid/corrections","doctor_a",body));assertEquals(first,retry);
            assertEquals("amended diagnosis",first.path("after").path("diagnosis").asText());
            JsonNode page=data(invoke("GET","/patient/appointments/unpaid/record/corrections","patient_a",null));
            assertEquals(1,page.path("total").asInt());assertEquals(5,page.path("pageSize").asInt());assertEquals("private reason sentinel",page.path("items").get(0).path("reason").asText());
            JsonNode details=data(invoke("GET","/doctor/records/record-unpaid","doctor_a",null));assertEquals(1,details.path("currentRevision").asInt());
            assertEquals("虚构诊断",details.path("original").path("diagnosis").asText());assertEquals("amended diagnosis",details.path("effective").path("diagnosis").asText());
            assertEquals(details,data(invoke("GET","/patient/appointments/unpaid/record/details","patient_a",null)));
            List<AuditEvent> rows=events(AuditEventType.RECORD_CORRECTION_APPEND);assertEquals(2,rows.size());
            assertEquals(1,rows.stream().filter(e -> e.getResult()==AuditResult.SUCCESS).count());assertEquals(1,rows.stream().filter(e -> e.getResult()==AuditResult.NO_CHANGE).count());
            assertFalse(assertions.writeValueAsString(rows).contains("private reason sentinel"));logs.assertAbsent("private reason sentinel","amended diagnosis");
        }
    }
    @Test void authorizedPageSizeSixRejected400() throws Exception {
        error(invoke("GET","/doctor/records/record-unpaid/corrections?pageSize=6","doctor_a",null),400,"CLINICAL_INPUT_INVALID");
        error(invoke("GET","/patient/appointments/unpaid/record/corrections?pageSize=6","patient_a",null),400,"CLINICAL_INPUT_INVALID");
    }
    static String repeat(String value,int count) { StringBuilder text=new StringBuilder();for(int i=0;i<count;i++)text.append(value);return text.toString(); }
    @Test void fiveFullLengthUnicodeCorrectionsFitOneMiB() throws Exception {
        // Full BMP Unicode fields exercise actual UTF8, including before and after, without
        // relying on H2's different supplementary-character VARCHAR length semantics.
        String c=repeat("中",500),a=repeat("文",2000),reason=repeat("因",2000);
        transaction(em -> {em.createQuery("delete from ClinicalRecord r where r.id='record-unpaid'").executeUpdate();em.persist(new ClinicalRecord("record-unpaid","unpaid","tenant-a","doctor-a","patient-a",c,c,a,a,BEFORE));return null;});
        for(int i=0;i<5;i++)data(invoke("POST","/doctor/records/record-unpaid/corrections","doctor_a",correction(i,UUID.randomUUID().toString(),content(c,c,a,a),reason)));
        MockHttpResponse response=invoke("GET","/patient/appointments/unpaid/record/corrections","patient_a",null);
        JsonNode page=data(response);assertEquals(5,page.path("total").asInt());assertEquals(5,page.path("items").size());
        byte[] actual=response.getOutput();assertTrue(actual.length<=1024*1024,"actual provider UTF8 bytes="+actual.length);
        System.out.println("CLINICAL_CORRECTION_PAGE_UTF8_BYTES="+actual.length);
        for(int i=0;i<5;i++) {JsonNode row=page.path("items").get(i);assertEquals(i+1,row.path("sequence").asInt());assertEquals(a,row.path("before").path("notes").asText());assertEquals(a,row.path("after").path("advice").asText());assertEquals(reason,row.path("reason").asText());}
    }
    @Test void newErrorsHaveSafeRequestIdAndIndependentFailureAudit() throws Exception {
        try(AuditLogCapture logs=new AuditLogCapture()) {
            JsonNode failure=error(invoke("PUT","/patient/appointments/booked/complaint","patient_a","{\"description\":\"private-body-sentinel\",\"expectedRevision\":7}"),409,"CLINICAL_REVISION_CONFLICT");
            assertFalse(failure.toString().contains("private-body-sentinel"));
            List<AuditEvent> rows=events(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE);assertEquals(1,rows.size());assertEquals(failure.path("requestId").asText(),rows.get(0).getRequestId());assertEquals(AuditResult.FAILED,rows.get(0).getResult());assertEquals(AuditReasonCode.CONFLICT,rows.get(0).getReasonCode());
            assertNull(manager.find(com.medcloud.course.paas.appointment.Appointment.class,"booked").getPatientComplaint());
            logs.assertAbsent("private-body-sentinel");
        }
    }
    @Test void requestCannotOverrideActorTenantTime() throws Exception {
        String body="{\"description\":\"safe\",\"expectedRevision\":0,\"actorId\":\"patient-b\",\"tenantId\":\"tenant-b\",\"updatedAt\":\"1900-01-01T00:00:00Z\"}";
        MockHttpResponse response=invoke("PUT","/patient/appointments/booked/complaint","patient_a",body);
        // Container Jackson may reject unknown properties or ignore them, but can never honor them.
        assertTrue(response.getStatus()==200||response.getStatus()==400,new String(response.getOutput(),StandardCharsets.UTF_8));
        if(response.getStatus()==200) {
            AuditEvent event=events(AuditEventType.APPOINTMENT_COMPLAINT_UPDATE).get(0);assertEquals("patient-a",event.getActorId());assertEquals("tenant-a",event.getTenantId());assertTrue(event.getRecordedAt().toInstant().isAfter(Instant.parse("2020-01-01T00:00:00Z")));
        }
        assertEquals("patient-a",manager.find(com.medcloud.course.paas.appointment.Appointment.class,"booked").getPatientId());
    }
    @Test void frozenTenantCanReadHistoryButCannotWriteAndDisabledActorRejected() throws Exception {
        setTenantStatus("DISABLED");
        data(invoke("GET","/patient/appointments/unpaid/record/details","patient_a",null));
        data(invoke("GET","/doctor/records/record-unpaid/corrections","doctor_a",null));
        error(invoke("PUT","/doctor/appointments/progress/draft","doctor_a","{\"expectedRevision\":0}"),409,"CLINICAL_STATE_CONFLICT");
        String token=bearer("patient_a");externalUpdate("update Account a set a.status='DISABLED' where a.id='patient-a'");manager.clear();
        MockHttpRequest request=MockHttpRequest.get("/patient/appointments/unpaid/record/details");request.header("Authorization",token);MockHttpResponse response=new MockHttpResponse();dispatcher.invoke(request,response);error(response,401,"UNAUTHORIZED");
    }
}
