package com.medcloud.platform.schedule.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScheduleCatalogTest {
    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
    private static final String DEMO_HASH =
            "$2a$10$ucqsp8rSjN.zbcRRnijpDuSF/W7eqnYiifPs3K0wC00mmYGdj0DU2";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ScheduleCatalogTest(MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void patientFiltersCatalogByTenantDepartmentAndDateWithStablePagination() throws Exception {
        LocalDate date = LocalDate.now().plusDays(20);
        CatalogFixture matching = insertDirectory(TENANT_A, "心内科", "陈医生", "ACTIVE", "ACTIVE");
        insertSlot(matching, date, "09:00", "10:00", 2, "39.00", "PUBLISHED");
        insertSlot(matching, date, "10:00", "11:00", 4, "59.00", "PUBLISHED");
        CatalogFixture otherDepartment = insertDirectory(TENANT_A, "外科", "周医生", "ACTIVE", "ACTIVE");
        insertSlot(otherDepartment, date, "09:00", "10:00", 3, "49.00", "PUBLISHED");
        CatalogFixture otherTenant = insertDirectory(TENANT_B, "儿科", "吴医生", "ACTIVE", "ACTIVE");
        insertSlot(otherTenant, date, "09:00", "10:00", 3, "29.00", "PUBLISHED");

        mockMvc.perform(get("/api/catalog/schedule-slots")
                        .param("tenantId", TENANT_A)
                        .param("departmentId", matching.departmentId())
                        .param("date", date.toString())
                        .param("page", "1")
                        .param("size", "1")
                        .header(AUTHORIZATION, bearer("patient_demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.number").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.content[0].tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.data.content[0].tenantName").value("仁和云诊所"))
                .andExpect(jsonPath("$.data.content[0].departmentId").value(matching.departmentId()))
                .andExpect(jsonPath("$.data.content[0].departmentName").value("心内科"))
                .andExpect(jsonPath("$.data.content[0].doctorId").value(matching.doctorId()))
                .andExpect(jsonPath("$.data.content[0].doctorName").value("陈医生"))
                .andExpect(jsonPath("$.data.content[0].date").value(date.toString()))
                .andExpect(jsonPath("$.data.content[0].capacity").value(4))
                .andExpect(jsonPath("$.data.content[0].remainingCapacity").value(4))
                .andExpect(jsonPath("$.data.content[0].fee").value(59.00))
                .andExpect(jsonPath("$.data.content[0].status").value("PUBLISHED"));
    }

    @Test
    void catalogExcludesPastOrUnpublishedSlotsAndInactiveTenantDepartmentOrDoctor() throws Exception {
        LocalDate future = LocalDate.now().plusDays(20);
        CatalogFixture visible = insertDirectory(TENANT_A, "可见科室", "可见医生", "ACTIVE", "ACTIVE");
        String visibleSlot = insertSlot(visible, future, "08:00", "09:00", 2, "39.00", "PUBLISHED");

        CatalogFixture unpublished = insertDirectory(TENANT_A, "停用号源科室", "号源停用医生", "ACTIVE", "ACTIVE");
        insertSlot(unpublished, future, "09:00", "10:00", 2, "39.00", "DISABLED");

        CatalogFixture disabledDepartment = insertDirectory(
                TENANT_A, "停用科室", "科室停用医生", "DISABLED", "ACTIVE");
        insertSlot(disabledDepartment, future, "10:00", "11:00", 2, "39.00", "PUBLISHED");

        CatalogFixture disabledDoctor = insertDirectory(
                TENANT_A, "医生停用科室", "停用医生", "ACTIVE", "DISABLED");
        insertSlot(disabledDoctor, future, "11:00", "12:00", 2, "39.00", "PUBLISHED");

        CatalogFixture past = insertDirectory(TENANT_A, "过期科室", "过期医生", "ACTIVE", "ACTIVE");
        insertSlot(past, LocalDate.now().minusDays(1), "12:00", "13:00", 2, "39.00", "PUBLISHED");

        CatalogFixture disabledTenant = insertDirectory(TENANT_B, "租户停用科室", "租户停用医生", "ACTIVE", "ACTIVE");
        insertSlot(disabledTenant, future, "13:00", "14:00", 2, "39.00", "PUBLISHED");
        jdbcTemplate.update("update tenant set status = 'DISABLED' where id = ?", TENANT_B);

        mockMvc.perform(get("/api/catalog/schedule-slots")
                        .header(AUTHORIZATION, bearer("patient_demo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(visibleSlot))
                .andExpect(jsonPath("$.data.content[0].doctorName").value("可见医生"));
    }

    @Test
    void anonymousUserCannotBrowseScheduleCatalog() throws Exception {
        mockMvc.perform(get("/api/catalog/schedule-slots"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    private CatalogFixture insertDirectory(
            String tenantId,
            String departmentName,
            String doctorName,
            String departmentStatus,
            String doctorStatus
    ) {
        String departmentId = UUID.randomUUID().toString();
        String doctorId = UUID.randomUUID().toString();
        String username = "catalog_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                insert into department
                    (id, tenant_id, name, description, status, created_at, updated_at)
                values (?, ?, ?, '', ?, current_timestamp, current_timestamp)
                """, departmentId, tenantId, departmentName, departmentStatus);
        jdbcTemplate.update("""
                insert into user_account
                    (id, username, password_hash, role, status, tenant_id, created_at, updated_at)
                values (?, ?, ?, 'DOCTOR', ?, ?, current_timestamp, current_timestamp)
                """, doctorId, username, DEMO_HASH, doctorStatus, tenantId);
        jdbcTemplate.update("""
                insert into doctor_profile
                    (user_id, tenant_id, department_id, display_name, title, biography)
                values (?, ?, ?, ?, '医师', '')
                """, doctorId, tenantId, departmentId, doctorName);
        return new CatalogFixture(tenantId, departmentId, doctorId);
    }

    private String insertSlot(
            CatalogFixture fixture,
            LocalDate date,
            String startTime,
            String endTime,
            int capacity,
            String fee,
            String status
    ) {
        String slotId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into schedule_slot
                    (id, tenant_id, doctor_id, department_id, schedule_date, start_time, end_time,
                     capacity, fee, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, current_timestamp, current_timestamp)
                """, slotId, fixture.tenantId(), fixture.doctorId(), fixture.departmentId(), date,
                startTime, endTime, capacity, fee, status);
        return slotId;
    }

    private String bearer(String username) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Demo@123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(json).at("/data/accessToken").asText();
    }

    private record CatalogFixture(String tenantId, String departmentId, String doctorId) {
    }
}
