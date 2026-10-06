package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.persistence.*;
import javax.ws.rs.core.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditQueryTest extends AuditFixture {
    @Test void defaultsAreThirtyShanghaiDaysAndTwentyRows() {
        AuditQuery defaultQuery = AuditQuery.parse(params(), false);
        assertEquals(20, defaultQuery.getSize()); assertEquals(1, defaultQuery.getPage());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertEquals(today.toString(), defaultQuery.getTo());
        assertEquals(today.minusDays(29).toString(), defaultQuery.getFrom());
        assertEquals(0, defaultQuery.getOffset());
    }
    @Test void leapYearAndShanghaiHalfOpenBoundsAreExact() {
        AuditQuery q = AuditQuery.parse(params("from", "2024-01-01", "to", "2024-12-31"), true);
        assertEquals(Instant.parse("2023-12-31T16:00:00Z"), q.getLowerInclusive().toInstant());
        assertEquals(Instant.parse("2024-12-31T16:00:00Z"), q.getUpperExclusive().toInstant());
        assertNull(AuditQuery.parse(params("eventType", "  ", "result", " "), true).getEventType());
        assertNull(AuditQuery.parse(params("result", " "), true).getResult());
    }
    @ParameterizedTest @ValueSource(strings={"from=2024-01-01", "to=2024-01-01", "from=2023-02-29&to=2023-03-01",
        "from=2024-01-01&to=2025-01-01", "from=0999-12-31&to=1000-01-01", "from=9999-12-31&to=9999-12-31",
        "from=2026-10-02&to=2026-10-01", "from=2026-1-01&to=2026-01-01", "eventType=unknown", "result=success",
        "tenantId=not-a-uuid", "tenantId=", "tenantId=1-1-1-1-1", "page=0", "size=0", "size=101", "page=abc",
        "page=2147483648", "page=2147483647&size=100", "size=99999999999999", "page=", "objectId=x", "global=true"})
    void invalidQueryNeverBroadensTheScope(String raw) {
        assertThrows(IllegalArgumentException.class, () -> AuditQuery.parse(rawParams(raw), true), raw);
    }
    @Test void duplicatesAndHospitalTenantOverridesAreRejected() {
        for (String key : Arrays.asList("from","to","eventType","result","tenantId","page","size")) {
            MultivaluedMap<String,String> p = params(); p.add(key, ""); p.add(key, "");
            assertThrows(IllegalArgumentException.class, () -> AuditQuery.parse(p, true));
        }
        assertThrows(IllegalArgumentException.class, () -> AuditQuery.parse(params("tenantId", B), false));
        AuditQuery max = AuditQuery.parse(params("page", "2147483647", "size", "1"), true);
        assertEquals(2147483646, max.getOffset());
    }
    @Test void hospitalSeesOnlyOwnTenantIncludingDisabled() {
        update("update tenant set status='DISABLED' where id=?1", A);
        AuditPage page = service.hospital(ADMIN, query());
        assertEquals(2, page.getTotal()); assertEquals(2, page.getItems().size());
        assertEquals("00000000-0000-0000-0000-000000000002", page.getItems().get(0).getId());
        assertEquals(A, page.getItems().get(0).getTenantId());
        assertEquals("虚构医院甲", page.getItems().get(0).getTenantName());
        assertEquals("2026-10-01T12:00:00+08:00", page.getItems().get(0).getRecordedAt());
        assertEquals("Asia/Shanghai", page.getTimezone());
    }
    @Test void platformSeesGlobalOrSelectedExistingTenant() {
        AuditPage all = service.platform(PLATFORM, query());
        assertEquals(4, all.getTotal());
        assertEquals("全局/归属未验证", all.getItems().get(0).getTenantName());
        AuditPage selected = service.platform(PLATFORM, AuditQuery.parse(range("tenantId", B), true));
        assertEquals(1, selected.getTotal()); assertEquals(B, selected.getItems().get(0).getTenantId());
        assertThrows(IllegalArgumentException.class, () -> service.platform(PLATFORM,
            AuditQuery.parse(range("tenantId", "30000000-0000-0000-0000-000000000000"), true)));
        assertThrows(IllegalArgumentException.class, () -> service.hospital(ADMIN, AuditQuery.parse(range("tenantId", B), true)));
    }
    @Test void disabledActorRejectedAndServiceRevalidatesAfterPrecheck() {
        service.requireReader(ADMIN, false);
        update("update user_account set status='DISABLED' where id=?1", ADMIN.getUserId());
        assertThrows(SecurityException.class, () -> service.hospital(ADMIN, query()));
        assertThrows(SecurityException.class, () -> service.requireReader(ADMIN, false));
        assertThrows(SecurityException.class, () -> service.platform(null, query()));
    }
    @Test void roleAndAffiliationComeFromCurrentDatabase() {
        assertThrows(AccessDeniedException.class, () -> service.requireReader(PATIENT, false));
        assertThrows(AccessDeniedException.class, () -> service.hospital(PLATFORM, query()));
        assertThrows(AccessDeniedException.class, () -> service.platform(ADMIN, query()));
        assertThrows(AccessDeniedException.class, () -> service.hospital(new CurrentActor("patient", Role.HOSPITAL_ADMIN, A), query()));
        update("update user_account set tenant_id=?1 where id=?2", "missing", ADMIN.getUserId());
        assertThrows(SecurityException.class, () -> service.hospital(ADMIN, query()));
    }
    @Test void countsAndRowsShareFilterAndPaginationIsStable() {
        AuditPage page = service.platform(PLATFORM, AuditQuery.parse(range("eventType", "LOGIN", "result", "SUCCESS", "size", "1", "page", "2"), true));
        assertEquals(2, page.getTotal()); assertEquals(1, page.getItems().size());
        assertEquals("00000000-0000-0000-0000-000000000001", page.getItems().get(0).getId());
        assertEquals(2, page.getPage()); assertEquals(1, page.getSize());
        AuditPage combined = service.platform(PLATFORM, AuditQuery.parse(range("tenantId", B, "eventType", "LOGIN", "result", "SUCCESS"), true));
        assertEquals(0, combined.getTotal()); assertTrue(combined.getItems().isEmpty());
        AuditPage noChange = service.platform(PLATFORM, AuditQuery.parse(range("tenantId", B, "eventType", "PAYMENT_PAY", "result", "NO_CHANGE"), true));
        assertEquals(1, noChange.getTotal()); assertEquals("00000000-0000-0000-0000-000000000003", noChange.getItems().get(0).getId());
        AuditPage beyond = service.platform(PLATFORM, AuditQuery.parse(range("page", "20"), true));
        assertEquals(4, beyond.getTotal()); assertTrue(beyond.getItems().isEmpty());
        AuditPage empty = service.platform(PLATFORM, AuditQuery.parse(params("from", "2026-10-02", "to", "2026-10-02"), true));
        assertEquals(0, empty.getTotal()); assertTrue(empty.getItems().isEmpty());
    }
    @Test void boundariesAreInclusiveThroughLastMicrosecondAndExcludeNextMidnight() {
        event(5, A, "LOGIN", "SUCCESS", "2026-10-01 00:00:00.000000");
        event(6, A, "LOGIN", "SUCCESS", "2026-10-01 23:59:59.999999");
        event(7, A, "LOGIN", "SUCCESS", "2026-10-02 00:00:00.000000");
        assertEquals(4, service.hospital(ADMIN, query()).getTotal());
    }
    @Test void auditQueriesAreReadOnlyAndNotTruncatedAtLegacyListLimits() {
        for (int i=10; i<215; i++) event(i, A, "LOGIN", "SUCCESS", "2026-10-01 12:00:00");
        long before = ((Number)manager.createNativeQuery("select count(*) from audit_event").getSingleResult()).longValue();
        AuditPage page = service.hospital(ADMIN, AuditQuery.parse(range("size", "100", "page", "3"), false));
        assertEquals(207, page.getTotal()); assertEquals(7, page.getItems().size());
        assertEquals(before, ((Number)manager.createNativeQuery("select count(*) from audit_event").getSingleResult()).longValue());
    }
}

abstract class AuditFixture {
    static final String A="10000000-0000-0000-0000-000000000000", B="20000000-0000-0000-0000-000000000000";
    static final String PASSWORD="Fictional123!", SECRET="fictional-audit-test-secret-at-least-32-characters";
    static final CurrentActor ADMIN=new CurrentActor("admin",Role.HOSPITAL_ADMIN,B), PLATFORM=new CurrentActor("platform",Role.PLATFORM_ADMIN,null), PATIENT=new CurrentActor("patient",Role.PATIENT,null);
    EntityManagerFactory factory; EntityManager manager; AuditQueryService service;
    @BeforeEach void auditFixture() {
        Map<String,Object> properties=new HashMap<String,Object>();
        properties.put("javax.persistence.jdbc.url","jdbc:h2:mem:audit_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        factory=Persistence.createEntityManagerFactory("medicalCloudCourseTest",properties); manager=factory.createEntityManager();
        manager.getTransaction().begin();
        manager.persist(new Tenant(A,"虚构医院甲")); manager.persist(new Tenant(B,"虚构医院乙"));
        String hash=org.mindrot.jbcrypt.BCrypt.hashpw(PASSWORD,org.mindrot.jbcrypt.BCrypt.gensalt(4));
        manager.persist(new Account("admin","admin",hash,Role.HOSPITAL_ADMIN,A));
        manager.persist(new Account("platform","platform",hash,Role.PLATFORM_ADMIN,null));
        manager.persist(new Account("patient","patient",hash,Role.PATIENT,null));
        manager.persist(new Account("doctor","doctor",hash,Role.DOCTOR,A));
        manager.flush();
        event(1,A,"LOGIN","SUCCESS","2026-10-01 12:00:00"); event(2,A,"LOGIN","SUCCESS","2026-10-01 12:00:00");
        event(3,B,"PAYMENT_PAY","NO_CHANGE","2026-10-01 13:00:00"); event(4,null,"LOGIN","FAILED","2026-10-01 14:00:00");
        manager.getTransaction().commit(); manager.clear(); service=new AuditQueryService(manager);
    }
    void update(String sql,Object...values) {
        boolean own=!manager.getTransaction().isActive(); if(own) manager.getTransaction().begin();
        Query q=manager.createNativeQuery(sql); for(int i=0;i<values.length;i++) q.setParameter(i+1,values[i]); q.executeUpdate();
        if(own) manager.getTransaction().commit();
    }
    void event(int id,String tenant,String event,String result,String at) {
        String uuid=String.format("00000000-0000-0000-0000-%012d",id);
        update("insert into audit_event(id,request_id,actor_id,actor_role,tenant_id,event_type,object_type,object_id,result,reason_code,recorded_at) values (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
            uuid,UUID.randomUUID().toString(),"private-actor",result.equals("FAILED")?null:"PATIENT",tenant,event,event.equals("LOGIN")?"ACCOUNT":"PAYMENT_ORDER",result.equals("FAILED")?null:"private-object",result,result.equals("SUCCESS")?"OK":result.equals("NO_CHANGE")?"UNCHANGED":"UNAUTHORIZED",Timestamp.from(LocalDateTime.parse(at.replace(' ','T')).atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
    }
    static MultivaluedMap<String,String> params(String...pairs) { MultivaluedMap<String,String> p=new MultivaluedHashMap<String,String>(); for(int i=0;i<pairs.length;i+=2) p.add(pairs[i],pairs[i+1]); return p; }
    static MultivaluedMap<String,String> rawParams(String raw) { MultivaluedMap<String,String> p=params(); for(String pair:raw.split("&")) { String[] kv=pair.split("=",-1); p.add(kv[0],kv[1]); } return p; }
    static MultivaluedMap<String,String> range(String...extra) { MultivaluedMap<String,String> p=params("from","2026-10-01","to","2026-10-01"); p.putAll(params(extra)); return p; }
    static AuditQuery query() { return AuditQuery.parse(range(),true); }
    @AfterEach void closeAudit() { if(manager!=null){ if(manager.getTransaction().isActive())manager.getTransaction().rollback(); manager.close(); } if(factory!=null)factory.close(); }
}
