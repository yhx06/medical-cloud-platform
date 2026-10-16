package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.Role;
import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

/** Reads only role-specific, server-selected clinical resources. */
public final class ClinicalSupplementWorkspace {
    private final PaasClient paas;
    public ClinicalSupplementWorkspace(PaasClient paas) { this.paas = paas; }

    public void load(HttpServletRequest request, String token, Role role, String targetId) {
        if (role != Role.PATIENT && role != Role.DOCTOR) throw new IllegalArgumentException("无临床访问权限");
        String id = id(targetId), path = request.getServletPath();
        request.setAttribute("targetId", id);
        request.setAttribute("clinicalWritable", false);
        if (role == Role.PATIENT && "/patient/complaint".equals(path)) {
            JsonNode complaint = paas.get("/patient/appointments/" + id + "/complaint", token);
            request.setAttribute("patientComplaint", complaint);
            request.setAttribute("description", complaint.path("description").asText(""));
            request.setAttribute("expectedRevision", complaint.path("revision").asText("0"));
            request.setAttribute("clinicalWritable", complaint.path("writable").isBoolean() && complaint.path("writable").asBoolean());
        } else if (role == Role.DOCTOR && "/doctor/clinical".equals(path)) {
            JsonNode workspace = paas.get("/doctor/appointments/" + id + "/clinical-workspace", token);
            request.setAttribute("clinicalWorkspace", workspace);
            request.setAttribute("patientComplaint", workspace.path("patientComplaint"));
            request.setAttribute("expectedRevision", workspace.path("draft").path("revision").asText("0"));
            fields(request, workspace.path("draft").path("content"));
            new WorkspaceAccess(paas).loadContext(request, token, role);
            request.setAttribute("clinicalWritable", Boolean.TRUE.equals(request.getAttribute("businessWritable")) && "IN_PROGRESS".equals(workspace.path("status").asText()));
        } else if ((role == Role.DOCTOR && ("/doctor/record-details".equals(path) || "/doctor/correction".equals(path)))
                || (role == Role.PATIENT && "/patient/record-details".equals(path))) {
            String base = role == Role.DOCTOR ? "/doctor/records/" + id : "/patient/appointments/" + id + "/record";
            JsonNode details = paas.get(base + (role == Role.PATIENT ? "/details" : ""), token);
            request.setAttribute("recordDetails", details);
            if ("/doctor/correction".equals(path)) {
                fields(request, details.path("effective"));
                request.setAttribute("expectedRevision", details.path("currentRevision").asText("0"));
                request.setAttribute("operationKey", UUID.randomUUID().toString());
                new WorkspaceAccess(paas).loadContext(request, token, role);
                request.setAttribute("clinicalWritable", Boolean.TRUE.equals(request.getAttribute("businessWritable")));
            } else {
                int page = number(request.getParameter("page"), 1, Integer.MAX_VALUE);
                int size = number(request.getParameter("pageSize"), 5, 5);
                if (((long) page - 1) * size > Integer.MAX_VALUE) throw new IllegalArgumentException("历史页码无效");
                request.setAttribute("correctionPage", paas.get(base + "/corrections?page=" + page + "&pageSize=" + size, token));
                request.setAttribute("historyPageSize", size);
                if (role == Role.DOCTOR) {
                    new WorkspaceAccess(paas).loadContext(request, token, role);
                    request.setAttribute("clinicalWritable", Boolean.TRUE.equals(request.getAttribute("businessWritable")));
                }
            }
        } else throw new IllegalArgumentException("临床入口无效");
    }
    private static int number(String value, int fallback, int max) {
        if (value == null || value.isEmpty()) return fallback;
        try { int n = Integer.parseInt(value); if (n > 0 && n <= max) return n; } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("历史页码或页长无效（每页最多5条）");
    }
    static void fields(HttpServletRequest request, JsonNode content) {
        for (String name : new String[]{"complaint", "diagnosis", "advice", "notes"}) request.setAttribute(name, content.path(name).asText(""));
    }
    static String id(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("编号格式无效");
        return value;
    }
}
