package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.*;
import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.UUID;
import static com.medcloud.course.contract.HospitalAdministrationRules.*;

/** Platform control-plane forms carry only safe display fields between requests. */
public final class PlatformAdministrationWorkspace {
    private final PaasClient paas;
    public PlatformAdministrationWorkspace(PaasClient paas) { this.paas = paas; }

    public void load(HttpServletRequest request, String token) {
        String path = request.getServletPath();
        // Every new page revalidates the current backend identity, including cached creation results.
        if (path.equals("/platform/hospital/administrators") || path.equals("/platform/hospital/administrator/create")) {
            String tenantId = requireTargetId(single(request, "tenantId"));
            int page = requirePage(single(request, "page"), 1, Integer.MAX_VALUE);
            int size = requirePage(single(request, "size"), 20, 100);
            requireOffset(page, size);
            JsonNode data = paas.get("/platform/tenants/" + segment(tenantId) + "/administrators?page=" + page + "&size=" + size, token);
            if (!data.isObject() || !data.path("items").isArray() || !data.path("tenant").isObject()
                    || !data.path("total").isIntegralNumber() || !data.path("activeAdministratorCount").isIntegralNumber()
                    || !tenantId.equals(data.path("tenant").path("id").asText())) throw new PaasUnavailableException(new IllegalStateException("invalid administrator page"));
            request.setAttribute("tenantId", tenantId);
            request.setAttribute("administrators", data);
        } else {
            // Use the existing authorized platform query. A session's remembered role is insufficient.
            paas.get("/platform/tenants?page=1&size=1", token);
        }
        if (path.equals("/platform/hospital/open") || path.equals("/platform/hospital/administrator/create"))
            request.setAttribute("operationKey", UUID.randomUUID().toString());
    }

    public HospitalAdminCreationView open(HttpServletRequest request, String token) {
        String name = requireHospitalName(single(request, "hospitalName"));
        return creation(paas.post("/platform/tenants", token, new HospitalOpenRequest(name,
                requireUsername(single(request, "username")), password(request), requireOperationKey(single(request, "operationKey")))));
    }

    public HospitalAdminCreationView create(HttpServletRequest request, String token) {
        String tenantId = requireTargetId(single(request, "tenantId"));
        return creation(paas.post("/platform/tenants/" + segment(tenantId) + "/administrators", token,
                new HospitalAdministratorCreateRequest(requireUsername(single(request, "username")), password(request),
                        requireOperationKey(single(request, "operationKey")))));
    }

    public void changeStatus(HttpServletRequest request, String token) {
        String tenantId = requireTargetId(single(request, "tenantId"));
        String adminId = requireTargetId(single(request, "adminId"));
        paas.put("/platform/tenants/" + segment(tenantId) + "/administrators/" + segment(adminId) + "/status", token,
                new TenantStatusRequest(requireStatus(single(request, "status")), requireStatus(single(request, "expectedStatus"))));
    }

    static String single(HttpServletRequest request, String key) {
        String[] values = request.getParameterValues(key);
        if (values == null) return null;
        if (values.length != 1) throw new IllegalArgumentException("duplicate input");
        return values[0];
    }
    private static String password(HttpServletRequest request) {
        String password = requireInitialPassword(single(request, "initialPassword"));
        if (!password.equals(single(request, "confirmInitialPassword"))) throw new IllegalArgumentException("password confirmation mismatch");
        return password;
    }
    private static HospitalAdminCreationView creation(JsonNode data) {
        try {
            String tenant = requireTargetId(text(data,"tenantId"));
            String administrator = requireTargetId(text(data,"administratorId"));
            String name = requireHospitalName(text(data,"hospitalName"));
            String username = requireUsername(text(data,"username"));
            return new HospitalAdminCreationView(tenant,name,administrator,username);
        } catch (IllegalArgumentException invalid) { throw new PaasUnavailableException(new IllegalStateException("invalid creation result")); }
    }
    private static String text(JsonNode data,String key) {
        if (!data.isObject() || !data.path(key).isTextual()) throw new IllegalArgumentException("invalid result");
        return data.path(key).textValue();
    }
    public static String query(String value) {
        try { return URLEncoder.encode(value,"UTF-8"); } catch (UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String segment(String value) { return query(value).replace("+", "%20"); }
}
