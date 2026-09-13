package com.medcloud.platform.tenant.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.platform.identity.domain.UserAccount;
import com.medcloud.platform.identity.infrastructure.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HospitalDirectoryTest {
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    HospitalDirectoryTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder
    ) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Test
    void departmentNameMustBeUniqueWithinTenant() throws Exception {
        String token = bearer("hospital_a");

        mockMvc.perform(post("/api/hospital/departments").header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"内科\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/hospital/departments").header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"内科\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTMENT_DUPLICATE"));
    }

    @Test
    void hospitalAdminCreatesDoctorInOwnDepartmentWithBcryptPassword() throws Exception {
        String token = bearer("hospital_a");
        String departmentId = createDepartment(token, "心内科", "心血管专科");
        String username = "doctor_" + UUID.randomUUID().toString().replace("-", "");

        mockMvc.perform(post("/api/hospital/doctors").header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "Doctor@123456",
                                "departmentId", departmentId,
                                "displayName", "李医生",
                                "title", "主任医师",
                                "biography", "擅长心血管疾病诊疗"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.tenantId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.data.departmentId").value(departmentId))
                .andExpect(jsonPath("$.data.displayName").value("李医生"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        UserAccount account = users.findByUsername(username).orElseThrow();
        assertThat(passwordEncoder.matches("Doctor@123456", account.getPasswordHash())).isTrue();
        assertThat(account.getRole().name()).isEqualTo("DOCTOR");
        assertThat(account.getTenantId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void hospitalAdminListsAndUpdatesOwnDepartments() throws Exception {
        String token = bearer("hospital_a");
        String departmentId = createDepartment(token, "外科", "普通外科");

        mockMvc.perform(put("/api/hospital/departments/{id}", departmentId)
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"普外科\",\"description\":\"普外门诊\",\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("普外科"))
                .andExpect(jsonPath("$.data.description").value("普外门诊"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(get("/api/hospital/departments").header(AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(departmentId));
    }

    @Test
    void hospitalAdminListsDoctorsAndChangesDoctorStatus() throws Exception {
        String token = bearer("hospital_a");
        String departmentId = createDepartment(token, "儿科", null);
        String doctorId = createDoctor(token, departmentId, "王医生");

        mockMvc.perform(put("/api/hospital/doctors/{id}/status", doctorId)
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(get("/api/hospital/doctors").header(AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(doctorId))
                .andExpect(jsonPath("$.data[0].displayName").value("王医生"));
    }

    @Test
    void unsupportedDepartmentStatusReturnsValidationError() throws Exception {
        String token = bearer("hospital_a");
        String departmentId = createDepartment(token, "口腔科", null);

        mockMvc.perform(put("/api/hospital/departments/{id}", departmentId)
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"口腔科\",\"description\":\"\",\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unsupportedDoctorStatusReturnsValidationError() throws Exception {
        String token = bearer("hospital_a");
        String departmentId = createDepartment(token, "眼科", null);
        String doctorId = createDoctor(token, departmentId, "赵医生");

        mockMvc.perform(put("/api/hospital/doctors/{id}/status", doctorId)
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @ParameterizedTest(name = "{0} cannot call {1} {2}")
    @MethodSource("nonHospitalDirectoryRequests")
    void nonHospitalRolesCannotAccessDirectoryEndpoints(
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
    }

    @ParameterizedTest(name = "anonymous cannot call {0} {1}")
    @MethodSource("directoryRequests")
    void unauthenticatedUsersCannotAccessDirectoryEndpoints(
            HttpMethod method,
            String uri,
            String body
    ) throws Exception {
        mockMvc.perform(request(method, uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    private static Stream<Arguments> nonHospitalDirectoryRequests() {
        return Stream.of("patient_demo", "doctor_a", "platform_admin")
                .flatMap(username -> directoryRequests()
                        .map(request -> Arguments.of(username, request.get()[0], request.get()[1], request.get()[2])));
    }

    private static Stream<Arguments> directoryRequests() {
        String id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/hospital/departments", ""),
                Arguments.of(HttpMethod.POST, "/api/hospital/departments", "{\"name\":\"越权科室\"}"),
                Arguments.of(HttpMethod.PUT, "/api/hospital/departments/" + id,
                        "{\"name\":\"越权科室\",\"description\":\"\",\"status\":\"ACTIVE\"}"),
                Arguments.of(HttpMethod.GET, "/api/hospital/doctors", ""),
                Arguments.of(HttpMethod.POST, "/api/hospital/doctors",
                        "{\"username\":\"unauthorized_doctor\",\"password\":\"Doctor@123456\","
                                + "\"departmentId\":\"" + id + "\",\"displayName\":\"越权医生\"}"),
                Arguments.of(HttpMethod.PUT, "/api/hospital/doctors/" + id + "/status",
                        "{\"status\":\"DISABLED\"}"));
    }

    private String createDepartment(String token, String name, String description) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name,
                "description", description == null ? "" : description));
        String response = mockMvc.perform(post("/api/hospital/departments")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/id").asText();
    }

    private String createDoctor(String token, String departmentId, String displayName) throws Exception {
        String username = "doctor_" + UUID.randomUUID().toString().replace("-", "");
        String response = mockMvc.perform(post("/api/hospital/doctors")
                        .header(AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "Doctor@123456",
                                "departmentId", departmentId,
                                "displayName", displayName,
                                "title", "医师",
                                "biography", ""))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.at("/data/id").asText();
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }
}
