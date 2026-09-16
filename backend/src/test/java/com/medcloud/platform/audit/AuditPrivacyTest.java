package com.medcloud.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.audit.application.AuditService;
import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditEvent;
import com.medcloud.platform.audit.domain.AuditObjectType;
import com.medcloud.platform.audit.domain.AuditResult;
import com.medcloud.platform.audit.infrastructure.AuditEventRepository;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditPrivacyTest {
    @MockitoSpyBean
    private AuditService audit;

    @Autowired
    private AuditEventRepository events;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void auditEventContainsOnlySafeActorAndObjectIdentifiers() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        CurrentUser actor = new CurrentUser(actorId, "clinician", UserRole.DOCTOR, tenantId);

        UUID requestId = UUID.randomUUID();
        audit.record(actor, AuditAction.APPOINTMENT_BOOKED, AuditObjectType.APPOINTMENT, appointmentId,
                AuditResult.REJECTED, requestId);

        AuditEvent savedAudit = events.findAll().stream()
                .filter(event -> actorId.equals(event.getActorId()))
                .findFirst()
                .orElseThrow();
        String serialized = objectMapper.writeValueAsString(savedAudit);

        assertThat(savedAudit.getActorId()).isEqualTo(actorId);
        assertThat(savedAudit.getTenantId()).isEqualTo(tenantId);
        assertThat(savedAudit.getObjectId()).isEqualTo(appointmentId);
        assertThat(savedAudit.getRequestId()).isEqualTo(requestId);
        assertThat(serialized).doesNotContain(
                "诊断", "Bearer", "Demo@123456", "body", "token", "password",
                "complaint", "diagnosis", "advice", "prescription", "filename", "url");
    }

    @Test
    void auditEventFactoryAcceptsOnlyTypedEnumsAndUuidRequestIdentifiers() {
        UUID requestId = UUID.randomUUID();

        AuditEvent event = AuditEvent.create(
                UUID.randomUUID(), UUID.randomUUID(), AuditAction.APPOINTMENT_BOOKED,
                AuditObjectType.APPOINTMENT, UUID.randomUUID(), AuditResult.SUCCESS, requestId);

        assertThat(event.getObjectType()).isEqualTo(AuditObjectType.APPOINTMENT);
        assertThat(event.getRequestId()).isEqualTo(requestId);
        assertThat(AuditEvent.create(
                null, null, AuditAction.LOGIN_REJECTED, AuditObjectType.USER_ACCOUNT,
                null, AuditResult.REJECTED, null).getRequestId()).isNull();
        assertThatThrownBy(() -> AuditEvent.create(
                null, null, null, AuditObjectType.USER_ACCOUNT, null, AuditResult.REJECTED, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void databaseRejectsAuditEventsWithAnInvalidAction() {
        assertAuditConstraintViolation("NOT_AN_ACTION", "TENANT", "SUCCESS",
                "00000000-0000-0000-0000-000000000001");
    }

    @Test
    void databaseRejectsAuditEventsWithAnInvalidObjectType() {
        assertAuditConstraintViolation("LOGIN_SUCCEEDED", "UNKNOWN_OBJECT", "SUCCESS",
                "00000000-0000-0000-0000-000000000002");
    }

    @Test
    void databaseRejectsAuditEventsWithAnInvalidResult() {
        assertAuditConstraintViolation("LOGIN_SUCCEEDED", "TENANT", "NOT_A_RESULT",
                "00000000-0000-0000-0000-000000000003");
    }

    @Test
    void databaseRejectsAuditEventsWithNonHexadecimalUuidRequestIds() {
        assertAuditConstraintViolation("LOGIN_SUCCEEDED", "TENANT", "SUCCESS",
                "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz");
    }

    @Test
    void mutationBoundariesRecordSuccessfulAndRejectedOutcomesWithoutRequestContent() throws Exception {
        String token = bearer("platform_admin");
        UUID existingTenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String tenantName = "审计医院-" + UUID.randomUUID();

        mockMvc.perform(post("/api/platform/tenants")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tenantName + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/platform/tenants/{tenantId}/status", existingTenantId)
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isBadRequest());

        assertThat(events.findAll()).anySatisfy(event -> {
            assertThat(event.getAction()).isEqualTo(AuditAction.TENANT_CREATED);
            assertThat(event.getResult()).isEqualTo(AuditResult.SUCCESS);
            assertThat(event.getObjectType()).isEqualTo(AuditObjectType.TENANT);
        }).anySatisfy(event -> {
            assertThat(event.getAction()).isEqualTo(AuditAction.TENANT_STATUS_CHANGED);
            assertThat(event.getResult()).isEqualTo(AuditResult.REJECTED);
            assertThat(event.getObjectId()).isEqualTo(existingTenantId);
        });
        assertThat(objectMapper.writeValueAsString(events.findAll()))
                .doesNotContain(tenantName, "Demo@123456", "password", "body");
    }

    @Test
    void rejectedHospitalAdminCreationDoesNotUseTheTenantIdAsAnAccountObjectId() throws Exception {
        mockMvc.perform(post("/api/platform/tenants/{tenantId}/admins", UUID.randomUUID())
                        .header(AUTHORIZATION, bearer("platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"missing_tenant_admin\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isNotFound());

        assertThat(events.findAll()).anySatisfy(event -> {
            assertThat(event.getAction()).isEqualTo(AuditAction.HOSPITAL_ADMIN_CREATED);
            assertThat(event.getResult()).isEqualTo(AuditResult.REJECTED);
            assertThat(event.getObjectType()).isEqualTo(AuditObjectType.USER_ACCOUNT);
            assertThat(event.getObjectId()).isNull();
        });
    }

    @Test
    void auditOutageDoesNotTurnCommittedMutationIntoAnErrorResponse() throws Exception {
        doThrow(new IllegalStateException("audit store unavailable"))
                .when(audit).record(any(), eq(AuditAction.TENANT_CREATED), eq(AuditObjectType.TENANT), any(),
                        eq(AuditResult.SUCCESS), any());

        mockMvc.perform(post("/api/platform/tenants")
                        .header(AUTHORIZATION, bearer("platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"审计故障成功-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void auditOutageDoesNotReplaceRejectedBusinessException() throws Exception {
        doThrow(new IllegalStateException("audit store unavailable"))
                .when(audit).record(any(), eq(AuditAction.TENANT_STATUS_CHANGED), eq(AuditObjectType.TENANT), any(),
                        eq(AuditResult.REJECTED), any());

        mockMvc.perform(patch("/api/platform/tenants/{tenantId}/status",
                                "11111111-1111-1111-1111-111111111111")
                        .header(AUTHORIZATION, bearer("platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }

    private void assertAuditConstraintViolation(
            String action,
            String objectType,
            String result,
            String requestId
    ) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into audit_event
                            (id, action, object_type, result, request_id, created_at)
                        values (?, ?, ?, ?, ?, current_timestamp)
                        """, UUID.randomUUID().toString(), action, objectType, result, requestId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
