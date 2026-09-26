package com.medcloud.course.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loginResponseHasStableRoleAndEnvelopeFields() throws Exception {
        ApiResponse<LoginResponse> response = ApiResponse.success(
                "登录成功", new LoginResponse("demo-token", "patient_demo", Role.PATIENT));

        String json = mapper.writeValueAsString(response);

        assertEquals("OK", mapper.readTree(json).get("code").asText());
        assertEquals("登录成功", mapper.readTree(json).get("message").asText());
        assertEquals("PATIENT", mapper.readTree(json).at("/data/role").asText());
        assertEquals("demo-token", mapper.readTree(json).at("/data/accessToken").asText());
    }

    @Test
    void errorResponseNeverRequiresMedicalData() throws Exception {
        ApiError error = new ApiError("AUTH_FORBIDDEN", "无权访问", "request-123");

        String json = mapper.writeValueAsString(error);

        assertEquals("AUTH_FORBIDDEN", mapper.readTree(json).get("code").asText());
        assertEquals("request-123", mapper.readTree(json).get("requestId").asText());
        assertEquals(false, mapper.readTree(json).has("data"));
    }
}
