package com.medcloud.platform.tenant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RoleAuthorizationTest {
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    RoleAuthorizationTest(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @Test
    void platformTenantListIsRestrictedToPlatformAdmin() throws Exception {
        mockMvc.perform(get("/api/platform/tenants").header("Authorization", bearer("platform_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(get("/api/platform/tenants").header("Authorization", bearer("patient_demo")))
                .andExpect(status().isForbidden());
    }

    @Test
    void hospitalProfileIsRestrictedToTenantStaff() throws Exception {
        mockMvc.perform(get("/api/hospital/profile").header("Authorization", bearer("doctor_a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("仁和云诊所"));
        mockMvc.perform(get("/api/hospital/profile").header("Authorization", bearer("patient_demo")))
                .andExpect(status().isForbidden());
    }

    @Test
    void patientProfileIsRestrictedToPatients() throws Exception {
        mockMvc.perform(get("/api/patient/profile").header("Authorization", bearer("patient_demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("patient_demo"));
        mockMvc.perform(get("/api/patient/profile").header("Authorization", bearer("doctor_a")))
                .andExpect(status().isForbidden());
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }
}
