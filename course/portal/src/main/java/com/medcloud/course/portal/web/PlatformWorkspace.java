package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.TenantStatusRequest;
import javax.servlet.http.HttpServletRequest;

/** Platform hospital management contains only safe list/status data. */
public final class PlatformWorkspace {
    private final PaasClient paas;
    public PlatformWorkspace(PaasClient paas) { this.paas = paas; }

    public void load(HttpServletRequest request, String token) {
        request.removeAttribute("tenants");
        try {
            int page = integer(request, "page", 1, Integer.MAX_VALUE);
            int size = integer(request, "size", 20, 100);
            request.setAttribute("page", page); request.setAttribute("size", size);
            JsonNode tenants = paas.get("/platform/tenants?page=" + page + "&size=" + size, token);
            if (!tenants.isObject() || !tenants.path("items").isArray()
                    || !tenants.path("total").isIntegralNumber()) {
                throw new IllegalArgumentException("医院列表服务返回的信息不完整，请稍后重试");
            }
            request.setAttribute("tenants", tenants);
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException failure) {
            WorkspaceAccess.rethrowUnauthorized(failure);
            request.setAttribute("tenantListError", WebEscaper.html(failure.getMessage()));
        }
    }

    public void changeStatus(HttpServletRequest request, String token) {
        String id = StatisticsWorkspace.parameter(request, "id");
        String status = StatisticsWorkspace.parameter(request, "status");
        String expected = StatisticsWorkspace.parameter(request, "expectedStatus");
        if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("请输入有效编号");
        }
        if (!knownStatus(status) || !knownStatus(expected)) throw new IllegalArgumentException("医院状态无效");
        paas.put("/platform/tenants/" + id + "/status", token, new TenantStatusRequest(status, expected));
    }

    public String redirectQuery(HttpServletRequest request) {
        StringBuilder query = new StringBuilder();
        for (String key : new String[] {"page", "size"}) {
            if (!StatisticsWorkspace.parameter(request, key).isEmpty()) {
                try { append(query, key, String.valueOf(integer(request, key, key.equals("page") ? 1 : 20,
                        key.equals("page") ? Integer.MAX_VALUE : 100))); }
                catch (IllegalArgumentException ignored) { /* Invalid input must not survive a redirect. */ }
            }
        }
        try {
            if (!StatisticsWorkspace.rangeQuery(request).isEmpty()) {
                append(query, "statsFrom", StatisticsWorkspace.parameter(request, "statsFrom"));
                append(query, "statsTo", StatisticsWorkspace.parameter(request, "statsTo"));
            }
        } catch (IllegalArgumentException ignored) { /* Keep only a complete valid range. */ }
        try {
            AuditWorkspace.query(request, com.medcloud.course.contract.Role.PLATFORM_ADMIN);
            for (String key : new String[]{"auditFrom", "auditTo", "auditEventType", "auditResult", "auditPage", "auditSize", "auditTenantId"}) {
                String value = StatisticsWorkspace.parameter(request, key);
                if (!value.isEmpty()) append(query, key, value);
            }
        } catch (IllegalArgumentException ignored) { /* Invalid audit input must not survive a redirect. */ }
        return query.length() == 0 ? "" : "?" + query;
    }

    private static void append(StringBuilder query, String key, String value) {
        if (query.length() > 0) query.append('&'); query.append(key).append('=').append(value);
    }

    private static int integer(HttpServletRequest request, String key, int fallback, int maximum) {
        String value = StatisticsWorkspace.parameter(request, key);
        if (value.isEmpty()) return fallback;
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            int number = Integer.parseInt(value);
            if (number < 1 || number > maximum) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException failure) { throw new IllegalArgumentException("医院列表分页参数无效"); }
    }

    private static boolean knownStatus(String status) { return "ACTIVE".equals(status) || "DISABLED".equals(status); }
}
