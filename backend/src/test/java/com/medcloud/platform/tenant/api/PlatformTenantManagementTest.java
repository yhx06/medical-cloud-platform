package com.medcloud.platform.tenant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.tenant.application.PlatformTenantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlatformTenantManagementTest {
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @MockitoSpyBean
    private PlatformTenantService tenantService;

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

    @ParameterizedTest(name = "{0} cannot call {1} {2}")
    @MethodSource("nonPlatformMutationRequests")
    void nonPlatformRolesCannotCallPrivilegedMutations(
            String username,
            HttpMethod method,
            String uri,
            String body
    ) throws Exception {
        mockMvc.perform(request(method, uri)
                        .header(AUTHORIZATION, bearer(username))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        verifyNoInteractions(tenantService);
    }

    private static Stream<Arguments> nonPlatformMutationRequests() {
        return Stream.of("patient_demo", "doctor_a", "hospital_a")
                .flatMap(username -> Stream.of(
                        Arguments.of(username, HttpMethod.POST, "/api/platform/tenants",
                                "{\"name\":\"越权医院\"}"),
                        Arguments.of(username, HttpMethod.POST,
                                "/api/platform/tenants/11111111-1111-1111-1111-111111111111/admins",
                                "{\"username\":\"unauthorized_admin\",\"password\":\"Demo@123456\"}"),
                        Arguments.of(username, HttpMethod.PATCH,
                                "/api/platform/tenants/11111111-1111-1111-1111-111111111111/status",
                                "{\"status\":\"DISABLED\"}"),
                        Arguments.of(username, HttpMethod.PATCH,
                                "/api/platform/accounts/20000000-0000-0000-0000-000000000001/status",
                                "{\"status\":\"DISABLED\"}")));
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }
}
