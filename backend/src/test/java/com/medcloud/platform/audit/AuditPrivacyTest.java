package com.medcloud.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.audit.application.AuditService;
import com.medcloud.platform.audit.domain.AuditAction;
import com.medcloud.platform.audit.domain.AuditEvent;
import com.medcloud.platform.audit.domain.AuditResult;
import com.medcloud.platform.audit.infrastructure.AuditEventRepository;
import com.medcloud.platform.identity.domain.UserRole;
import com.medcloud.platform.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditPrivacyTest {
    @Autowired
    private AuditService audit;

    @Autowired
    private AuditEventRepository events;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void auditEventContainsOnlySafeActorAndObjectIdentifiers() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        CurrentUser actor = new CurrentUser(actorId, "clinician", UserRole.DOCTOR, tenantId);

        audit.record(actor, AuditAction.APPOINTMENT_BOOKED, "APPOINTMENT", appointmentId,
                AuditResult.REJECTED, "request-8");

        AuditEvent savedAudit = events.findAll().stream()
                .filter(event -> actorId.equals(event.getActorId()))
                .findFirst()
                .orElseThrow();
        String serialized = objectMapper.writeValueAsString(savedAudit);

        assertThat(savedAudit.getActorId()).isEqualTo(actorId);
        assertThat(savedAudit.getTenantId()).isEqualTo(tenantId);
        assertThat(savedAudit.getObjectId()).isEqualTo(appointmentId);
        assertThat(serialized).doesNotContain(
                "诊断", "Bearer", "Demo@123456", "body", "token", "password",
                "complaint", "diagnosis", "advice", "prescription", "filename", "url");
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
            assertThat(event.getObjectType()).isEqualTo("TENANT");
        }).anySatisfy(event -> {
            assertThat(event.getAction()).isEqualTo(AuditAction.TENANT_STATUS_CHANGED);
            assertThat(event.getResult()).isEqualTo(AuditResult.REJECTED);
            assertThat(event.getObjectId()).isEqualTo(existingTenantId);
        });
        assertThat(objectMapper.writeValueAsString(events.findAll()))
                .doesNotContain(tenantName, "Demo@123456", "password", "body");
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }
}
