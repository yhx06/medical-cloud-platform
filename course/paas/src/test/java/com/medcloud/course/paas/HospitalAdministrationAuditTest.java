package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.tenant.*;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.junit.jupiter.api.Test;
import javax.ejb.EJBException;
import javax.persistence.PersistenceException;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HospitalAdministrationAuditTest extends HospitalAdministrationDispatchFixture {
    // Runner duplication, event mismatch or lost request identity breaks persisted audit assertions.
    @Test void openingAndRetryHaveExactlyOneEventEachAndSeparateServerRequestIds() throws Exception {
        String body=openingJson("audit_admin");
        MockHttpResponse first=invoke("POST","/platform/tenants","platform_1",body);
        JsonNode opened=data(first);
        MockHttpResponse retry=invoke("POST","/platform/tenants","platform_1",body);
        assertEquals(opened,data(retry));
        List<AuditEvent> rows=events(AuditEventType.TENANT_OPEN);assertEquals(2,rows.size());
        assertEquals(1,rows.stream().filter(e -> e.getResult()==AuditResult.SUCCESS).count());
        assertEquals(1,rows.stream().filter(e -> e.getResult()==AuditResult.NO_CHANGE).count());
        assertNotEquals(rows.get(0).getRequestId(),rows.get(1).getRequestId());
        Set<String> responseIds=new HashSet<>(Arrays.asList(first.getOutputHeaders().getFirst("X-Request-ID").toString(),retry.getOutputHeaders().getFirst("X-Request-ID").toString()));
        for(AuditEvent row:rows){assertTrue(responseIds.contains(row.getRequestId()));assertNotEquals(CLIENT_ID,row.getRequestId());assertEquals("platform-1",row.getActorId());assertEquals(opened.path("tenantId").asText(),row.getTenantId());assertEquals(row.getTenantId(),row.getObjectId());}
        assertEquals(1,count(HospitalAdminOperation.class));assertEquals(3,count(Tenant.class));
    }

    @Test void createAndStatusUseTheirOwnSuccessAndNoChangeEventTypes() throws Exception {
        String creation=creationJson("audit_second");
        JsonNode added=data(invoke("POST","/platform/tenants/tenant-a/administrators","platform_1",creation));
        data(invoke("POST","/platform/tenants/tenant-a/administrators","platform_1",creation));
        String path="/platform/tenants/tenant-a/administrators/"+added.path("administratorId").asText()+"/status";
        data(invoke("PUT",path,"platform_1",statusJson("DISABLED","ACTIVE")));
        data(invoke("PUT",path,"platform_1",statusJson("DISABLED","ACTIVE")));
        for(AuditEventType type:Arrays.asList(AuditEventType.HOSPITAL_ADMIN_CREATE,AuditEventType.HOSPITAL_ADMIN_STATUS_CHANGE)) {
            List<AuditEvent> rows=events(type);assertEquals(2,rows.size());
            assertEquals(1,rows.stream().filter(e -> e.getResult()==AuditResult.SUCCESS).count());assertEquals(1,rows.stream().filter(e -> e.getResult()==AuditResult.NO_CHANGE).count());
            for(AuditEvent row:rows){assertEquals(added.path("administratorId").asText(),row.getObjectId());assertEquals("tenant-a",row.getTenantId());}
        }
    }

    @Test void domainErrorsMapFiveCodesAndAuditReasonsThroughWrappedFailures() throws Exception {
        for(String code:Arrays.asList("HOSPITAL_ONBOARDING_INVALID","USERNAME_EXISTS","OPERATION_KEY_CONFLICT","ADMIN_STATUS_CONFLICT","LAST_ACTIVE_HOSPITAL_ADMIN")) {
            int status="HOSPITAL_ONBOARDING_INVALID".equals(code)?400:409;
            injectedFailure=new EJBException(new HospitalAdministrationException(status,code));
            MockHttpResponse response=invoke("POST","/platform/tenants","platform_1",openingJson("error_admin"));
            JsonNode failure=error(response,status,code);
            assertEquals(failure.path("requestId").asText(),response.getOutputHeaders().getFirst("X-Request-ID"));
            assertEquals(status==400?AuditReasonCode.BAD_REQUEST:AuditReasonCode.CONFLICT,ApiFailureMapper.reasonFor(injectedFailure));
            AuditEvent row=events(AuditEventType.TENANT_OPEN).stream().filter(e -> e.getRequestId().equals(failure.path("requestId").asText())).findFirst().get();
            assertEquals(AuditResult.FAILED,row.getResult());assertEquals(status==400?AuditReasonCode.BAD_REQUEST:AuditReasonCode.CONFLICT,row.getReasonCode());
            assertEquals("platform-1",row.getActorId());assertNull(row.getTenantId());assertNull(row.getObjectId());
        }
        assertEquals(0,count(HospitalAdminOperation.class));
    }

    @Test void nativeFailureAndPasswordNeverReachResponseOrAuditAndFailureScopeIsPlatform() throws Exception {
        String sentinel="private-sql-parameter-sentinel";
        injectedFailure=new EJBException(new PersistenceException(new SQLException("INSERT user_account password="+PASSWORD+" "+sentinel,"23000",1062)));
        try(AuditLogCapture logs=new AuditLogCapture()) {
            MockHttpResponse response=invoke("POST","/platform/tenants/tenant-a/administrators","platform_1",creationJson("failed_admin"));
            JsonNode failure=error(response,500,"INTERNAL_ERROR");assertFalse(text(response).contains(sentinel));assertFalse(text(response).contains("SQLException"));
            assertEquals(failure.path("requestId").asText(),response.getOutputHeaders().getFirst("X-Request-ID"));
            List<AuditEvent> rows=events(AuditEventType.HOSPITAL_ADMIN_CREATE);assertEquals(1,rows.size());AuditEvent row=rows.get(0);
            assertEquals(failure.path("requestId").asText(),row.getRequestId());assertEquals(AuditReasonCode.INTERNAL_ERROR,row.getReasonCode());
            assertNull(row.getObjectId());assertNull(row.getTenantId());assertEquals("platform-1",row.getActorId());
            assertFalse(json.writeValueAsString(row).contains(sentinel));assertFalse(json.writeValueAsString(row).contains(PASSWORD));logs.assertAbsent(PASSWORD,sentinel);
        }
        assertEquals(0,count(HospitalAdminOperation.class));
    }

    @Test void realBusinessConflictsRollbackAndPreserveSafeFailureScope() throws Exception {
        error(invoke("POST","/platform/tenants","platform_1",openingJson("DOCTOR_A")),409,"USERNAME_EXISTS");
        error(invoke("PUT","/platform/tenants/tenant-a/administrators/admin-a/status","platform_1",statusJson("DISABLED","ACTIVE")),409,"LAST_ACTIVE_HOSPITAL_ADMIN");
        for(AuditEventType type:Arrays.asList(AuditEventType.TENANT_OPEN,AuditEventType.HOSPITAL_ADMIN_STATUS_CHANGE)) {
            AuditEvent row=events(type).get(0);assertEquals(AuditResult.FAILED,row.getResult());assertNull(row.getTenantId());assertNull(row.getObjectId());
        }
        assertEquals(2,count(Tenant.class));assertEquals(0,count(HospitalAdminOperation.class));
    }
}
