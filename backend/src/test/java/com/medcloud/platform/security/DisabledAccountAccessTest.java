package com.medcloud.platform.security;

import com.medcloud.platform.identity.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DisabledAccountAccessTest {
    private final MockMvc mockMvc;
    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    DisabledAccountAccessTest(MockMvc mockMvc, JwtService jwtService, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void rejectsPreviouslyIssuedTokenWhenAccountIsDisabled() throws Exception {
        String disabledToken = bearer(new CurrentUser(
                UUID.fromString("50000000-0000-0000-0000-000000000001"),
                "disabled_user", UserRole.PATIENT, null));

        mockMvc.perform(get("/api/me").header(AUTHORIZATION, disabledToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void rejectsPreviouslyIssuedTokenWhenTenantIsDisabled() throws Exception {
        jdbcTemplate.update("UPDATE tenant SET status = 'DISABLED' WHERE id = ?",
                "11111111-1111-1111-1111-111111111111");
        String doctorToken = bearer(new CurrentUser(
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "doctor_a", UserRole.DOCTOR,
                UUID.fromString("11111111-1111-1111-1111-111111111111")));

        mockMvc.perform(get("/api/me").header(AUTHORIZATION, doctorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_DISABLED"));
    }

    private String bearer(CurrentUser user) {
        return "Bearer " + jwtService.issue(user);
    }
}
