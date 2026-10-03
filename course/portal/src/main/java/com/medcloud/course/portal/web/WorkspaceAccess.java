package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.Role;
import javax.servlet.http.HttpServletRequest;

/** Protected section reads degrade independently, except an expired authorization. */
public final class WorkspaceAccess {
    private final PaasClient paas;
    public WorkspaceAccess(PaasClient paas) { this.paas = paas; }

    public void loadContext(HttpServletRequest request, String token, Role role) {
        request.setAttribute("businessWritable", Boolean.FALSE);
        request.removeAttribute("hospitalContext");
        try {
            String path;
            if (role == Role.HOSPITAL_ADMIN) path = "/hospital/context";
            else if (role == Role.DOCTOR) path = "/doctor/context";
            else throw new IllegalArgumentException("当前账号没有医院状态权限");
            JsonNode context = paas.get(path, token);
            String status = context.path("status").asText();
            if (!context.isObject() || !context.path("id").isTextual() || context.path("id").asText().isEmpty()
                    || !context.path("name").isTextual() || !("ACTIVE".equals(status) || "DISABLED".equals(status))
                    || !context.path("businessWritable").isBoolean()) {
                throw new IllegalArgumentException("医院状态未知，暂时仅可查询历史，请刷新后重试");
            }
            request.setAttribute("hospitalContext", context);
            request.setAttribute("businessWritable", "ACTIVE".equals(status) && context.path("businessWritable").asBoolean());
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException failure) {
            rethrowUnauthorized(failure);
            sectionError(request, "contextError", failure);
        }
    }

    public void loadSection(HttpServletRequest request, String token, String attribute, String path) {
        request.removeAttribute(attribute);
        try { request.setAttribute(attribute, paas.get(path, token)); }
        catch (PaasRejectedException | PaasUnavailableException failure) { sectionError(request, attribute + "Error", failure); }
    }

    public void clearPrivateAttributes(HttpServletRequest request) {
        for (String name : new String[] {"appointments", "records", "record", "orders", "order", "tenants", "statistics",
                "hospitalContext", "businessWritable", "notice", "initialPassword", "username", "csrfToken", "portalTitle",
                "appointmentId", "from", "to", "status", "statsFrom", "statsTo", "page", "size", "error",
                "appointmentsError", "recordsError", "recordError", "ordersError", "orderError", "tenantListError", "statisticsError",
                "contextError", "departmentsError", "doctorsError", "slotsError"}) request.removeAttribute(name);
        // Patient directory attributes are public; hospital resources use the same names privately.
        if (!"/patient".equals(request.getServletPath())) {
            for (String name : new String[] {"departments", "doctors", "slots"}) request.removeAttribute(name);
        }
    }

    static void rethrowUnauthorized(RuntimeException failure) {
        if (failure instanceof PaasRejectedException && ((PaasRejectedException) failure).getStatus() == 401) throw failure;
    }

    static void sectionError(HttpServletRequest request, String name, RuntimeException failure) {
        rethrowUnauthorized(failure);
        String escaped = WebEscaper.html(failure.getMessage()); request.setAttribute(name, escaped);
        Object previous = request.getAttribute("error");
        request.setAttribute("error", previous == null ? escaped : previous + "；" + escaped);
    }
}
