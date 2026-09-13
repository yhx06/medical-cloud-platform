package com.medcloud.platform.tenant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlatformTenantManagementTest {
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    PlatformTenantManagementTest(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @Test
    void platformAdminCreatesActiveTenant() throws Exception {
        mockMvc.perform(post("/api/platform/tenants").header(AUTHORIZATION, bearer("platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"明德云医院\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("明德云医院"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void platformAdminCreatesHospitalAdminForTenant() throws Exception {
        String tenantResponse = mockMvc.perform(post("/api/platform/tenants")
                        .header(AUTHORIZATION, bearer("platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"明德云医院\"}"))
                .andReturn().getResponse().getContentAsString();
        String tenantId = objectMapper.readTree(tenantResponse).at("/data/id").asText();
        String username = "hospital_" + UUID.randomUUID().toString().replace("-", "");

        mockMvc.perform(post("/api/platform/tenants/{tenantId}/admins", tenantId)
                        .header(AUTHORIZATION, bearer("platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.role").value("HOSPITAL_ADMIN"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.tenantId").value(tenantId));
    }

    @Test
    void tenantStatusEndpointRejectsUnsupportedStatus() throws Exception {
        mockMvc.perform(patch("/api/platform/tenants/{tenantId}/status",
                                "11111111-1111-1111-1111-111111111111")
                        .header(AUTHORIZATION, bearer("platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void platformAdminChangesTenantStatus() throws Exception {
        mockMvc.perform(patch("/api/platform/tenants/{tenantId}/status",
                                "22222222-2222-2222-2222-222222222222")
                        .header(AUTHORIZATION, bearer("platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    @Test
    void platformAdminChangesAccountStatus() throws Exception {
        mockMvc.perform(patch("/api/platform/accounts/{accountId}/status",
                                "20000000-0000-0000-0000-000000000002")
                        .header(AUTHORIZATION, bearer("platform_admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }
}
