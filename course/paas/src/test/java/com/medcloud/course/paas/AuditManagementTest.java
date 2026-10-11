package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.schedule.*;
import com.medcloud.course.paas.tenant.*;
import com.medcloud.course.paas.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.sql.Time;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditManagementTest extends TenantFixture {
    static final String USERNAME = "source_username_sentinel";
    static final String CLINICAL = "source_clinical_sentinel";
    private String initialPassword;
    private final AuditLogCapture logs = new AuditLogCapture();
    @AfterEach void safeLogs() { try { logs.assertAbsent(USERNAME, CLINICAL, initialPassword); } finally { logs.close(); } }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"PUT,/platform/tenants/tenant-b/status,TENANT_STATUS_CHANGE", "POST,/hospital/departments,DEPARTMENT_CREATE", "PUT,/hospital/departments/department-b,DEPARTMENT_UPDATE", "POST,/hospital/doctors,DOCTOR_CREATE", "PUT,/hospital/doctors/doctor-b,DOCTOR_UPDATE", "POST,/hospital/slots,SLOT_CREATE", "PUT,/hospital/slots/slot-b,SLOT_UPDATE", "POST,/auth/login,LOGIN"})
    void auditedHttpFailuresUseTheServerRequestId(String method, String path, AuditEventType type) throws Exception {
        org.jboss.resteasy.core.Dispatcher dispatcher = org.jboss.resteasy.mock.MockDispatcherFactory.createDispatcher();
        dispatcher.getProviderFactory().registerProvider(ApiFailureMapper.class);
        dispatcher.getProviderFactory().registerProvider(AuditRequestFilter.class);
        dispatcher.getProviderFactory().registerProvider(AuditResponseFilter.class);
        dispatcher.getProviderFactory().registerProvider(PlatformTenantDispatchTest.JsonWriter.class);
        dispatcher.getProviderFactory().registerProvider(JsonReader.class);
        AuditOperationRunner runner = AuditLocalTransactions.runner(manager);
        AuthService auth = new AuthService(manager, SECRET);
        dispatcher.getRegistry().addSingletonResource(new AuthResource(auth, runner));
        dispatcher.getRegistry().addSingletonResource(new PlatformTenantResource(auth, new PlatformTenantService(manager), runner));
        dispatcher.getRegistry().addSingletonResource(new HospitalResource(auth, new HospitalDepartmentService(manager), new HospitalDoctorService(manager), runner));
        dispatcher.getRegistry().addSingletonResource(new HospitalSlotResource(auth, new SlotAdminService(manager), runner));
        org.jboss.resteasy.mock.MockHttpRequest request = method.equals("POST") ? org.jboss.resteasy.mock.MockHttpRequest.post(path) : org.jboss.resteasy.mock.MockHttpRequest.put(path);
        String spoofed = "00000000-0000-0000-0000-000000000099";
        request.header("Content-Type", "application/json");
        request.content("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.header("Authorization", "Bearer invalid_token_sentinel"); request.header("X-Request-ID", spoofed);
        org.jboss.resteasy.mock.MockHttpResponse response = new org.jboss.resteasy.mock.MockHttpResponse();
        dispatcher.invoke(request, response);
        assertEquals(401, response.getStatus());
        com.fasterxml.jackson.databind.JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        String id = body.path("requestId").asText(); UUID.fromString(id);
        assertNotEquals(spoofed, id); assertEquals(id, response.getOutputHeaders().getFirst("X-Request-ID"));
        List<AuditEvent> rows = manager.createQuery("select e from AuditEvent e where e.requestId=:id", AuditEvent.class).setParameter("id",id).getResultList();
        assertEquals(1L,rows.size()); AuditEvent row=rows.get(0); assertEquals(type,row.getEventType());
        assertEquals(AuditResult.FAILED,row.getResult()); assertEquals(AuditReasonCode.UNAUTHORIZED,row.getReasonCode());
        assertNull(row.getActorId()); assertNull(row.getActorRole()); assertNull(row.getTenantId()); assertNull(row.getObjectId());
        logs.assertAbsent("invalid_token_sentinel");
    }

    @Test void invalidTokenManagementFailureHasNoActorOrTenant() throws Exception {
        HospitalResource resource = new HospitalResource(new AuthService(manager, SECRET), new HospitalDepartmentService(manager), new HospitalDoctorService(manager), AuditLocalTransactions.runner(manager));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> resource.updateDepartment("Bearer invalid_token_sentinel", "department-b", new DepartmentRequest(CLINICAL, "ACTIVE")));
        assertEquals(401, new ApiFailureMapper().toResponse(failure).getStatus());
        List<AuditEvent> rows = manager.createQuery("select e from AuditEvent e", AuditEvent.class).getResultList();
        assertEquals(1, rows.size()); AuditEvent e = rows.get(0);
        assertNull(e.getActorId()); assertNull(e.getActorRole()); assertNull(e.getTenantId()); assertNull(e.getObjectId());
        assertEquals(AuditReasonCode.UNAUTHORIZED, e.getReasonCode()); assertEquals(AuditEventType.DEPARTMENT_UPDATE, e.getEventType());
        assertFalse(new ObjectMapper().writeValueAsString(rows).contains("invalid_token_sentinel"));
        logs.assertAbsent("invalid_token_sentinel");
    }

    @Test void managementCrossTenantFailureNeverKeepsRequestedObjectId() throws Exception {
        AuthService auth = new AuthService(manager, SECRET);
        manager.getTransaction().begin(); String token = auth.login(new LoginRequest("admin_a", PASSWORD)).getAccessToken(); manager.getTransaction().commit();
        // Explicit local proxy adapter ends its failed transaction before the runner's failure writer.
        HospitalDepartmentService service = new HospitalDepartmentService(manager) {
            @Override public Department update(CurrentActor a, String id, String name, String status, AuditContext c) {
                manager.getTransaction().begin();
                try { Department result = super.update(a,id,name,status,c); manager.getTransaction().commit(); return result; }
                catch (RuntimeException failure) { if(manager.getTransaction().isActive()) manager.getTransaction().rollback(); throw failure; }
            }
        };
        HospitalResource resource = new HospitalResource(auth, service, new HospitalDoctorService(manager), AuditLocalTransactions.runner(manager));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> resource.updateDepartment("Bearer " + token, "department-b", new DepartmentRequest(CLINICAL, "ACTIVE")));
        assertEquals(403, new ApiFailureMapper().toResponse(failure).getStatus());
        List<AuditEvent> rows = manager.createQuery("select e from AuditEvent e where e.eventType=:type", AuditEvent.class).setParameter("type", AuditEventType.DEPARTMENT_UPDATE).getResultList();
        assertEquals(1, rows.size()); AuditEvent e = rows.get(0);
        assertEquals("admin-a", e.getActorId()); assertEquals(Role.HOSPITAL_ADMIN, e.getActorRole()); assertEquals("tenant-a", e.getTenantId());
        assertNull(e.getObjectId()); assertEquals(AuditReasonCode.FORBIDDEN, e.getReasonCode());
        assertEquals("全科", manager.find(Department.class,"department-b").getName());
    }

    @ParameterizedTest
    @EnumSource(value = AuditEventType.class, names = {"TENANT_STATUS_CHANGE", "DEPARTMENT_CREATE", "DEPARTMENT_UPDATE", "DOCTOR_CREATE", "DOCTOR_UPDATE", "SLOT_CREATE", "SLOT_UPDATE"})
    void journalFailureRollsBackEveryManagementMutation(AuditEventType type) {
        AuditContext context = AuditContext.create(type, UUID.randomUUID().toString());
        context.attachActor(new CurrentActor(type == AuditEventType.TENANT_STATUS_CHANGE ? "platform" : "admin-a", type == AuditEventType.TENANT_STATUS_CHANGE ? Role.PLATFORM_ADMIN : Role.HOSPITAL_ADMIN, type == AuditEventType.TENANT_STATUS_CHANGE ? null : "tenant-a"));
        context.attachVerifiedTenant("tenant-a");
        manager.getTransaction().begin(); new AuditJournal(manager).append(context,"preexisting-object",AuditResult.SUCCESS,java.time.Instant.now()); manager.getTransaction().commit();
        List<String> before = snapshot(); String tenantBefore = manager.find(Tenant.class,"tenant-a").getStatus();
        manager.getTransaction().begin();
        RuntimeException failure = assertThrows(AuditInfrastructureException.class, () -> mutate(type, context));
        assertEquals(500, new ApiFailureMapper().toResponse(failure).getStatus());
        manager.getTransaction().rollback(); manager.clear();
        assertEquals(before, snapshot()); assertEquals(tenantBefore, manager.find(Tenant.class,"tenant-a").getStatus());
        assertEquals(1L, manager.createQuery("select count(e) from AuditEvent e",Long.class).getSingleResult());
    }

    // Missing append, a wrong target, or trusting caller tenant instead of persisted ownership must fail.
    @ParameterizedTest
    @EnumSource(value = AuditEventType.class, names = {"TENANT_STATUS_CHANGE", "DEPARTMENT_CREATE", "DEPARTMENT_UPDATE", "DOCTOR_CREATE", "DOCTOR_UPDATE", "SLOT_CREATE", "SLOT_UPDATE"})
    void eachManagementOperationProducesExactlyOneCorrectEvent(AuditEventType type) throws Exception {
        java.time.Instant before = java.time.Instant.now();
        manager.getTransaction().begin();
        String target = mutate(type);
        manager.getTransaction().commit(); manager.clear();
        List<AuditEvent> rows = manager.createQuery("select e from AuditEvent e", AuditEvent.class).getResultList();
        assertEquals(1L, rows.size());
        AuditEvent e = rows.get(0);
        assertEquals(1L, manager.createQuery("select count(e) from AuditEvent e where e.requestId=:id", Long.class).setParameter("id", e.getRequestId()).getSingleResult());
        assertEquals(type, e.getEventType());
        String object = type == AuditEventType.TENANT_STATUS_CHANGE ? "TENANT" : type.name().startsWith("DEPARTMENT") ? "DEPARTMENT" : type.name().startsWith("DOCTOR") ? "DOCTOR_ACCOUNT" : "SLOT";
        assertEquals(object, e.getObjectType().name()); assertEquals(target, e.getObjectId());
        assertEquals("tenant-a", e.getTenantId());
        assertEquals(type == AuditEventType.TENANT_STATUS_CHANGE ? "platform" : "admin-a", e.getActorId());
        assertEquals(type == AuditEventType.TENANT_STATUS_CHANGE ? Role.PLATFORM_ADMIN : Role.HOSPITAL_ADMIN, e.getActorRole());
        assertEquals(AuditResult.SUCCESS, e.getResult()); assertEquals(AuditReasonCode.OK, e.getReasonCode());
        assertFalse(e.getRecordedAt().toInstant().isBefore(before.minusMillis(1)));
        assertFalse(e.getRecordedAt().toInstant().isAfter(java.time.Instant.now()));
        String serialized = new ObjectMapper().writeValueAsString(rows);
        assertFalse(serialized.contains(USERNAME)); assertFalse(serialized.contains(CLINICAL));
        if (initialPassword != null) assertFalse(serialized.contains(initialPassword));
    }

    // Container Jackson is absent in MockDispatcher; parse the real DTO input rather than replacing the resource.
    @javax.ws.rs.Consumes("application/json")
    public static class JsonReader implements javax.ws.rs.ext.MessageBodyReader<Object> {
        public boolean isReadable(Class<?> type, java.lang.reflect.Type generic, java.lang.annotation.Annotation[] annotations, javax.ws.rs.core.MediaType media) { return true; }
        public Object readFrom(Class<Object> type, java.lang.reflect.Type generic, java.lang.annotation.Annotation[] annotations,
                javax.ws.rs.core.MediaType media, javax.ws.rs.core.MultivaluedMap<String,String> headers, java.io.InputStream input) throws java.io.IOException {
            return new ObjectMapper().readValue(input, type);
        }
    }

    String mutate(AuditEventType type) {
        return mutate(type, null);
    }
    String mutate(AuditEventType type, AuditContext c) {
        switch (type) {
        case TENANT_STATUS_CHANGE: return c == null ? new PlatformTenantService(manager).changeStatus(PLATFORM, "tenant-a", new TenantStatusRequest("DISABLED", "ACTIVE")).getId() : new PlatformTenantService(manager).changeStatus(PLATFORM, "tenant-a", new TenantStatusRequest("DISABLED", "ACTIVE"),c).getId();
        case DEPARTMENT_CREATE: return c == null ? new HospitalDepartmentService(manager).create(ADMIN, CLINICAL).getId() : new HospitalDepartmentService(manager).create(ADMIN, CLINICAL,c).getId();
        case DEPARTMENT_UPDATE: return c == null ? new HospitalDepartmentService(manager).update(ADMIN, "department-a", CLINICAL, "ACTIVE").getId() : new HospitalDepartmentService(manager).update(ADMIN, "department-a", CLINICAL, "ACTIVE",c).getId();
        case DOCTOR_CREATE:
            DoctorCreateResponse created = c == null ? new HospitalDoctorService(manager).create(ADMIN, USERNAME, CLINICAL, "department-a") : new HospitalDoctorService(manager).create(ADMIN, USERNAME, CLINICAL, "department-a",c);
            initialPassword = created.getInitialPassword();
            assertTrue(org.mindrot.jbcrypt.BCrypt.checkpw(initialPassword, manager.find(Account.class, created.getUserId()).getPasswordHash()));
            return created.getUserId();
        case DOCTOR_UPDATE: return c == null ? new HospitalDoctorService(manager).update(ADMIN, "doctor-a", CLINICAL, "department-a", "ACTIVE").getUserId() : new HospitalDoctorService(manager).update(ADMIN, "doctor-a", CLINICAL, "department-a", "ACTIVE",c).getUserId();
        case SLOT_CREATE:
            SlotRequest create = request();
            create = new SlotRequest("department-a", "doctor-a", create.getDate(), Time.valueOf("11:00:00"), Time.valueOf("12:00:00"), 20, create.getFee(), "PUBLISHED");
            return c == null ? new SlotAdminService(manager).create(ADMIN, create).getId() : new SlotAdminService(manager).create(ADMIN, create,c).getId();
        case SLOT_UPDATE:
            SlotRequest old = request();
            SlotRequest edit = new SlotRequest(old.getDepartmentId(), old.getDoctorId(), old.getDate(), old.getStartTime(), old.getEndTime(), 21, old.getFee(), old.getStatus());
            return c == null ? new SlotAdminService(manager).update(ADMIN, "slot-a", edit).getId() : new SlotAdminService(manager).update(ADMIN, "slot-a", edit,c).getId();
        default: throw new AssertionError(type);
        }
    }

    @Test void repeatDesiredHospitalStateIsNoChange() {
        manager.getTransaction().begin();
        new PlatformTenantService(manager).changeStatus(PLATFORM, "tenant-a", new TenantStatusRequest("ACTIVE", "DISABLED"));
        manager.getTransaction().commit();
        List<AuditEvent> rows = manager.createQuery("select e from AuditEvent e", AuditEvent.class).getResultList();
        assertEquals(1L, rows.size());
        AuditEvent e = rows.get(0);
        assertEquals(AuditResult.NO_CHANGE, e.getResult()); assertEquals(AuditReasonCode.UNCHANGED, e.getReasonCode());
        assertEquals("ACTIVE", manager.find(Tenant.class, "tenant-a").getStatus());
    }
}
