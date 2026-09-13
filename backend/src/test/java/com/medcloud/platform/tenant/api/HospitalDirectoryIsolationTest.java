package com.medcloud.platform.tenant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HospitalDirectoryIsolationTest {
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    HospitalDirectoryIsolationTest(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void createSecondHospitalAdministrator() {
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, 'HOSPITAL_ADMIN', 'ACTIVE', ?, current_timestamp, current_timestamp)
                """,
                "30000000-0000-0000-0000-000000000002", "hospital_b", DEMO_HASH,
                "22222222-2222-2222-2222-222222222222");
    }

    @Test
    void hospitalCannotCreateDoctorInAnotherTenantsDepartment() throws Exception {
        String hospitalB = bearer("hospital_b");
        String foreignDepartmentId = createDepartment(hospitalB, "影像科");

        mockMvc.perform(post("/api/hospital/doctors")
                        .header(AUTHORIZATION, bearer("hospital_a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "foreign_" + UUID.randomUUID().toString().replace("-", ""),
                                "password", "Doctor@123456",
                                "departmentId", foreignDepartmentId,
                                "displayName", "越权医生",
                                "title", "医师",
                                "biography", ""))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
    }

    @Test
    void hospitalCannotUpdateAnotherTenantsDepartment() throws Exception {
        String foreignDepartmentId = createDepartment(bearer("hospital_b"), "检验科");

        mockMvc.perform(put("/api/hospital/departments/{id}", foreignDepartmentId)
                        .header(AUTHORIZATION, bearer("hospital_a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权修改\",\"description\":\"\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
    }

    @Test
    void hospitalCannotChangeAnotherTenantsDoctorStatus() throws Exception {
        String hospitalB = bearer("hospital_b");
        String departmentId = createDepartment(hospitalB, "康复科");
        String doctorId = createDoctor(hospitalB, departmentId);

        mockMvc.perform(put("/api/hospital/doctors/{id}/status", doctorId)
                        .header(AUTHORIZATION, bearer("hospital_a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
    }

    @Test
    void directoryListsContainOnlyActorsTenant() throws Exception {
        String hospitalA = bearer("hospital_a");
        String hospitalB = bearer("hospital_b");
        String departmentA = createDepartment(hospitalA, "租户A科室");
        String departmentB = createDepartment(hospitalB, "租户B科室");
        createDoctor(hospitalA, departmentA);
        createDoctor(hospitalB, departmentB);

        mockMvc.perform(get("/api/hospital/departments").header(AUTHORIZATION, hospitalA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("租户A科室"));

        mockMvc.perform(get("/api/hospital/doctors").header(AUTHORIZATION, hospitalA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].tenantId")
                        .value("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void forgedTenantIdsAreIgnoredWhenCreatingDepartmentsAndDoctors() throws Exception {
        String hospitalA = bearer("hospital_a");
        String hospitalB = bearer("hospital_b");
        String forgedTenantId = "22222222-2222-2222-2222-222222222222";

        String departmentResponse = mockMvc.perform(post("/api/hospital/departments")
                        .header(AUTHORIZATION, hospitalA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "认证租户科室",
                                "description", "客户端伪造租户字段",
                                "tenantId", forgedTenantId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andReturn().getResponse().getContentAsString();
        String departmentId = objectMapper.readTree(departmentResponse).at("/data/id").asText();

        mockMvc.perform(post("/api/hospital/doctors")
                        .header(AUTHORIZATION, hospitalA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "doctor_" + UUID.randomUUID().toString().replace("-", ""),
                                "password", "Doctor@123456",
                                "departmentId", departmentId,
                                "displayName", "认证租户医生",
                                "title", "医师",
                                "biography", "客户端伪造租户字段",
                                "tenantId", forgedTenantId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId")
                        .value("11111111-1111-1111-1111-111111111111"));

        mockMvc.perform(get("/api/hospital/departments").header(AUTHORIZATION, hospitalA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("认证租户科室"))
                .andExpect(jsonPath("$.data[0].tenantId")
                        .value("11111111-1111-1111-1111-111111111111"));
        mockMvc.perform(get("/api/hospital/doctors").header(AUTHORIZATION, hospitalA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].displayName").value("认证租户医生"))
                .andExpect(jsonPath("$.data[0].tenantId")
                        .value("11111111-1111-1111-1111-111111111111"));
        mockMvc.perform(get("/api/hospital/departments").header(AUTHORIZATION, hospitalB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/hospital/doctors").header(AUTHORIZATION, hospitalB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private String createDepartment(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/hospital/departments")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "description", ""))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/id").asText();
    }

    private String createDoctor(String token, String departmentId) throws Exception {
        String response = mockMvc.perform(post("/api/hospital/doctors")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "doctor_" + UUID.randomUUID().toString().replace("-", ""),
                                "password", "Doctor@123456",
                                "departmentId", departmentId,
                                "displayName", "测试医生",
                                "title", "医师",
                                "biography", ""))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/id").asText();
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }
}
