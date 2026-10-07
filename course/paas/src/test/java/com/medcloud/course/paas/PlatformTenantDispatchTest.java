package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.TenantPage;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.api.PlatformTenantResource;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.PlatformTenantService;
import org.jboss.resteasy.core.Dispatcher;
import org.jboss.resteasy.mock.MockDispatcherFactory;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.mock.MockHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import static org.junit.jupiter.api.Assertions.*;

class PlatformTenantDispatchTest extends TenantFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Dispatcher dispatcher;
    private AuthService auth;
    private int listCalls;

    @BeforeEach
    void dispatchFixture() {
        auth = new AuthService(manager, SECRET);
        // Observe only the boundary; authentication, account policy and list query remain real.
        PlatformTenantService tenants = new PlatformTenantService(manager) {
            @Override public TenantPage list(CurrentActor actor, int page, int size) {
                listCalls++;
                return super.list(actor, page, size);
            }
        };
        dispatcher = MockDispatcherFactory.createDispatcher();
        dispatcher.getProviderFactory().registerProvider(ApiFailureMapper.class);
        dispatcher.getProviderFactory().registerProvider(JsonWriter.class);
        dispatcher.getRegistry().addSingletonResource(new PlatformTenantResource(auth, tenants));
    }

    // Primitive query conversion before method invocation breaks both error precedence cases.
    @ParameterizedTest
    @ValueSource(strings = {"page=abc&size=20", "page=1&size=abc", "page=2147483648&size=20",
            "page=1&size=2147483648", "page=-2147483649&size=20", "page=1&size=-2147483649",
            "page=0&size=20", "page=1&size=0", "page=1&size=101", "page=2147483647&size=100"})
    void anonymousInvalidPaginationRequiresAuthentication(String query) throws Exception {
        assertRejected(query, null, 401, "UNAUTHORIZED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=abc&size=20", "page=1&size=abc", "page=2147483648&size=20",
            "page=1&size=2147483648", "page=-2147483649&size=20", "page=1&size=-2147483649",
            "page=0&size=20", "page=1&size=0", "page=1&size=101", "page=2147483647&size=100"})
    void platformInvalidPaginationIsPublicBadRequestBeforeListQuery(String query) throws Exception {
        assertRejected(query, bearer("platform"), 400, "BAD_REQUEST");
    }

    @ParameterizedTest
    @CsvSource({"admin_a,page=1&size=20", "admin_a,page=abc&size=20",
            "doctor_a,page=1&size=20", "doctor_a,page=1&size=abc",
            "patient_a,page=1&size=20", "patient_a,page=2147483648&size=20"})
    void wrongRoleIsForbiddenBeforePaginationValidation(String username, String query) throws Exception {
        assertRejected(query, bearer(username), 403, "FORBIDDEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer invalid", "Bearer ", "Basic invalid"})
    void invalidAuthorizationIsUnauthorizedBeforePaginationValidation(String authorization) throws Exception {
        assertRejected("page=abc&size=20", authorization, 401, "UNAUTHORIZED");
    }

    @Test
    void disabledAccountWithPreviouslyValidTokenRemainsUnauthorized() throws Exception {
        String authorization = bearer("platform");
        externalUpdate("update Account a set a.status = 'DISABLED' where a.id = 'platform'");
        assertRejected("page=2147483648&size=20", authorization, 401, "UNAUTHORIZED");
    }

    @ParameterizedTest
    @CsvSource({"'',1,20", "size=1,1,1", "page=2,2,20", "page=2&size=1,2,1"})
    void defaultsAndValidPaginationReturnRealHospitalList(String query, int page, int size) throws Exception {
        MockHttpResponse response = get(query, bearer("platform"));
        assertEquals(200, response.getStatus());
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals("OK", body.path("code").asText());
        JsonNode data = body.path("data");
        assertEquals(page, data.path("page").asInt());
        assertEquals(size, data.path("size").asInt());
        assertEquals(2, data.path("total").asInt());
        assertEquals(page == 1 ? Math.min(2, size) : (size == 1 ? 1 : 0), data.path("items").size());
        if (page == 2 && size == 1) assertEquals("tenant-b", data.path("items").get(0).path("id").asText());
        assertEquals(1, listCalls);
    }

    private void assertRejected(String query, String authorization, int status, String code) throws Exception {
        MockHttpResponse response = get(query, authorization);
        assertEquals(status, response.getStatus(), query);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals(code, body.path("code").asText());
        assertFalse(body.path("requestId").asText().isEmpty());
        assertFalse(body.has("data"));
        assertEquals(0, listCalls, "rejected requests must not call the tenant list service");
    }

    private MockHttpResponse get(String query, String authorization) throws Exception {
        MockHttpRequest request = MockHttpRequest.get("/platform/tenants" + (query.isEmpty() ? "" : "?" + query));
        if (authorization != null) request.header("Authorization", authorization);
        MockHttpResponse response = new MockHttpResponse();
        dispatcher.invoke(request, response);
        return response;
    }

    private String bearer(String username) {
        return "Bearer " + AuditLocalTransactions.login(manager, auth, new LoginRequest(username, PASSWORD)).getAccessToken();
    }

    // The container supplies Jackson; this test writer serializes the actual returned DTOs.
    @Produces(MediaType.APPLICATION_JSON)
    public static class JsonWriter implements MessageBodyWriter<Object> {
        public boolean isWriteable(Class<?> type, Type generic, Annotation[] annotations, MediaType media) { return true; }
        public long getSize(Object value, Class<?> type, Type generic, Annotation[] annotations, MediaType media) { return -1; }
        public void writeTo(Object value, Class<?> type, Type generic, Annotation[] annotations, MediaType media,
                MultivaluedMap<String, Object> headers, OutputStream output) throws IOException {
            JSON.writeValue(output, value);
        }
    }
}
