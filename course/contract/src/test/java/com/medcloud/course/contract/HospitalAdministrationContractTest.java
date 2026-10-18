package com.medcloud.course.contract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HospitalAdministrationContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String KEY = "12345678-1234-1234-1234-123456789abc";

    @Test
    void passwordBytesAndUnicodeAreExact() {
        assertEquals("12345678", HospitalAdministrationRules.requireInitialPassword("12345678"));
        assertEquals(repeat("a", 72), HospitalAdministrationRules.requireInitialPassword(repeat("a", 72)));
        assertEquals(repeat("院", 24), HospitalAdministrationRules.requireInitialPassword(repeat("院", 24)));
        assertEquals(" 123456 ", HospitalAdministrationRules.requireInitialPassword(" 123456 "));
        assertEquals("😀😀", HospitalAdministrationRules.requireInitialPassword("😀😀"));
        assertEquals("e\u030112345", HospitalAdministrationRules.requireInitialPassword("e\u030112345"));
        for (String invalid : new String[] {null, "", "1234567", repeat("a", 73), repeat("院", 24) + "a",
                "1234567\0", "1234567\n", "1234567\r", "1234567\t", "1234567\u007f",
                "1234567\u0085", "1234567\ud800", "1234567\udc00"}) {
            assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireInitialPassword(invalid));
        }
    }

    @Test
    void hospitalNameTrimsOnlyAsciiSpacesAfterCheckingOriginalText() {
        assertEquals("医院", HospitalAdministrationRules.requireHospitalName("  医院  "));
        assertEquals("\u00a0院\u00a0", HospitalAdministrationRules.requireHospitalName("\u00a0院\u00a0"));
        assertEquals(repeat("院", 100), HospitalAdministrationRules.requireHospitalName(repeat("院", 100)));
        assertEquals(repeat("😀", 50), HospitalAdministrationRules.requireHospitalName(repeat("😀", 50)));
        for (String invalid : new String[] {null, "", "   ", repeat("院", 101), repeat("😀", 50) + "a",
                "\t医院", "医院\n", "医院\0", "医院\u0085", "院\ud800", "\udc00院"}) {
            assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireHospitalName(invalid));
        }
    }

    @Test
    void usernameValidationPreservesSpellingAndKeysUseAsciiCaseFolding() {
        assertEquals("AbC_1", HospitalAdministrationRules.requireUsername("AbC_1"));
        assertEquals("abc_1", HospitalAdministrationRules.usernameKey("AbC_1"));
        assertEquals("abc", HospitalAdministrationRules.requireUsername("abc"));
        assertEquals(repeat("A", 64), HospitalAdministrationRules.requireUsername(repeat("A", 64)));
        for (String invalid : new String[] {null, "", "ab", repeat("a", 65), " abc", "abc ", "a-b", "用户a", "İab", "abc\n"}) {
            assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireUsername(invalid));
            assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.usernameKey(invalid));
        }
    }

    @Test
    void opaqueLegacyIdAndCanonicalKey() {
        assertEquals("tenant-a", HospitalAdministrationRules.requireTargetId("tenant-a"));
        assertEquals(repeat("x", 36), HospitalAdministrationRules.requireTargetId(repeat("x", 36)));
        for (String invalid : new String[] {null, "", repeat("x", 37), "a\0", "a\n", "a\u0085", "a\ud800", "a\udc00"}) {
            assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireTargetId(invalid));
        }
        assertEquals(KEY, HospitalAdministrationRules.requireOperationKey(KEY));
        for (String invalid : new String[] {null, "", "00000000-0000-0000-0000-000000000000", KEY.toUpperCase(java.util.Locale.ROOT),
                "1-1-1-1-1", " " + KEY, KEY + " ", KEY.replace('-', '_')}) {
            assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireOperationKey(invalid));
        }
    }

    @Test
    void statusesAndPaginationRejectInvalidValuesAndOverflow() {
        assertEquals("ACTIVE", HospitalAdministrationRules.requireStatus("ACTIVE"));
        assertEquals("DISABLED", HospitalAdministrationRules.requireStatus("DISABLED"));
        for (String invalid : new String[] {null, "", "active", "FROZEN", " ACTIVE"}) {
            assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireStatus(invalid));
        }
        assertEquals(1, HospitalAdministrationRules.requirePage(null, 1, Integer.MAX_VALUE));
        assertEquals(20, HospitalAdministrationRules.requirePage("", 20, 100));
        assertEquals(100, HospitalAdministrationRules.requirePage("100", 20, 100));
        for (String invalid : new String[] {"0", "-1", "101", "2147483648", " 1", "1 ", "+1", "1.0", "a"}) {
            assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requirePage(invalid, 20, 100));
        }
        assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requirePage(null, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requirePage(null, 101, 100));
        assertEquals(0, HospitalAdministrationRules.requireOffset(1, 20));
        assertEquals(40, HospitalAdministrationRules.requireOffset(3, 20));
        assertEquals(2147483646, HospitalAdministrationRules.requireOffset(Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireOffset(Integer.MAX_VALUE, 2));
        assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireOffset(0, 20));
        assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireOffset(1, 0));
        assertThrows(IllegalArgumentException.class, () -> HospitalAdministrationRules.requireOffset(1, 101));
    }

    @Test
    void validationErrorsNeverEchoInput() {
        String secret = "sensitive-input-\n";
        for (java.util.function.Function<String, String> rule : Arrays.<java.util.function.Function<String, String>>asList(
                HospitalAdministrationRules::requireHospitalName, HospitalAdministrationRules::requireUsername,
                HospitalAdministrationRules::usernameKey, HospitalAdministrationRules::requireInitialPassword,
                HospitalAdministrationRules::requireOperationKey, HospitalAdministrationRules::requireTargetId,
                HospitalAdministrationRules::requireStatus)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> rule.apply(secret));
            assertFalse(error.getMessage().contains("sensitive-input"));
            assertNull(error.getCause());
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HospitalAdministrationRules.requirePage("sensitive-page", 1, 100));
        assertFalse(error.getMessage().contains("sensitive-page"));
        assertNull(error.getCause());
    }

    @Test
    void requestJsonHasOnlyExplicitFieldsAndRejectsUnknownFieldsEvenForLenientMapper() throws Exception {
        HospitalOpenRequest open = new HospitalOpenRequest("医院", "Admin", "12345678", KEY);
        HospitalAdministratorCreateRequest create = new HospitalAdministratorCreateRequest("Admin", "12345678", KEY);
        assertFields(mapper.valueToTree(open), "hospitalName", "username", "initialPassword", "operationKey");
        assertFields(mapper.valueToTree(create), "username", "initialPassword", "operationKey");
        HospitalOpenRequest openRoundTrip = mapper.readValue(mapper.writeValueAsString(open), HospitalOpenRequest.class);
        assertEquals("医院", openRoundTrip.getHospitalName());
        assertEquals("Admin", openRoundTrip.getUsername());
        assertEquals("12345678", openRoundTrip.getInitialPassword());
        assertEquals(KEY, openRoundTrip.getOperationKey());
        HospitalAdministratorCreateRequest createRoundTrip = mapper.readValue(mapper.writeValueAsString(create), HospitalAdministratorCreateRequest.class);
        assertEquals("Admin", createRoundTrip.getUsername());
        assertEquals("12345678", createRoundTrip.getInitialPassword());
        assertEquals(KEY, createRoundTrip.getOperationKey());
        ObjectMapper lenient = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String field : new String[] {"role", "tenantId", "id", "passwordHash"}) {
            String json = "{\"" + field + "\":\"forbidden\"}";
            assertThrows(Exception.class, () -> lenient.readValue(json, HospitalOpenRequest.class));
            assertThrows(Exception.class, () -> lenient.readValue(json, HospitalAdministratorCreateRequest.class));
        }
        assertFalse(open.toString().contains("12345678"));
        assertFalse(create.toString().contains("12345678"));
    }

    @Test
    void responseJsonExcludesPasswordsAndPageKeepsTenantAndDefensiveList() throws Exception {
        HospitalAdminCreationView creation = new HospitalAdminCreationView("t", "院", "a", "user");
        JsonNode created = mapper.valueToTree(creation);
        assertFields(created, "tenantId", "hospitalName", "administratorId", "username");
        assertEquals("t", created.get("tenantId").asText());
        assertEquals("院", created.get("hospitalName").asText());
        assertEquals("a", created.get("administratorId").asText());
        assertEquals("user", created.get("username").asText());
        assertFalse(mapper.writeValueAsString(creation).contains("password"));
        HospitalAdministratorView admin = new HospitalAdministratorView("a", "t", "user", "ACTIVE");
        JsonNode administrator = mapper.valueToTree(admin);
        assertFields(administrator, "id", "tenantId", "username", "status");
        assertEquals("a", administrator.get("id").asText());
        assertEquals("t", administrator.get("tenantId").asText());
        assertEquals("user", administrator.get("username").asText());
        assertEquals("ACTIVE", administrator.get("status").asText());
        List<HospitalAdministratorView> input = new ArrayList<>();
        input.add(admin);
        HospitalAdministratorPage page = new HospitalAdministratorPage(new TenantView("t", "院", "FROZEN"), input, 5L, 2, 20, 3L);
        input.clear();
        assertEquals(1, page.getItems().size());
        assertThrows(UnsupportedOperationException.class, () -> page.getItems().clear());
        JsonNode json = mapper.valueToTree(page);
        assertFields(json, "tenant", "items", "total", "page", "size", "activeAdministratorCount");
        assertEquals("t", json.at("/tenant/id").asText());
        assertEquals("院", json.at("/tenant/name").asText());
        assertEquals("FROZEN", json.at("/tenant/status").asText());
        assertEquals(5L, json.get("total").asLong());
        assertEquals(2, json.get("page").asInt());
        assertEquals(20, json.get("size").asInt());
        assertEquals(3L, json.get("activeAdministratorCount").asLong());
        assertFalse(mapper.writeValueAsString(page).contains("password"));
    }

    @Test
    void hospitalAuditEventsIdentifyCorrectObjectTypes() {
        assertEquals(AuditObjectType.TENANT, AuditEventType.TENANT_OPEN.getObjectType());
        assertEquals(AuditObjectType.HOSPITAL_ADMIN_ACCOUNT, AuditEventType.HOSPITAL_ADMIN_CREATE.getObjectType());
        assertEquals(AuditObjectType.HOSPITAL_ADMIN_ACCOUNT, AuditEventType.HOSPITAL_ADMIN_STATUS_CHANGE.getObjectType());
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void assertFields(JsonNode node, String... fields) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(new HashSet<>(Arrays.asList(fields)), actual);
    }
}
