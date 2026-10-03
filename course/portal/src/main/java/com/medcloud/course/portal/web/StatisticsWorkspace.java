package com.medcloud.course.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medcloud.course.contract.Role;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;

/** Statistics uses its own filters; invalid input never becomes a default query. */
public final class StatisticsWorkspace {
    private final PaasClient paas;
    public StatisticsWorkspace(PaasClient paas) { this.paas = paas; }

    public void load(HttpServletRequest request, String token, Role role) {
        String from = parameter(request, "statsFrom");
        String to = parameter(request, "statsTo");
        request.setAttribute("statsFrom", WebEscaper.html(from));
        request.setAttribute("statsTo", WebEscaper.html(to));
        request.removeAttribute("statistics");
        try {
            String query = rangeQuery(request);
            String path;
            if (role == Role.PLATFORM_ADMIN) path = "/platform/statistics";
            else if (role == Role.HOSPITAL_ADMIN) path = "/hospital/statistics";
            else throw new IllegalArgumentException("当前账号没有统计权限");
            JsonNode report = paas.get(path + query, token);
            if (!report.isObject() || !report.path("metrics").isObject()
                    || !report.path("daily").isArray() || !report.path("hospitals").isArray()
                    || !report.path("legacyUnknownTime").isObject()
                    || !report.path("from").isTextual() || !report.path("to").isTextual()) {
                throw new IllegalArgumentException("统计服务返回的信息不完整，请稍后重试");
            }
            request.setAttribute("statistics", report);
            request.setAttribute("statsFrom", WebEscaper.html(report.path("from").asText()));
            request.setAttribute("statsTo", WebEscaper.html(report.path("to").asText()));
        } catch (PaasRejectedException | PaasUnavailableException | IllegalArgumentException failure) {
            WorkspaceAccess.rethrowUnauthorized(failure);
            request.setAttribute("statisticsError", WebEscaper.html(failure.getMessage()));
        }
    }

    // Shared with platform redirect validation, without broadening partially invalid ranges.
    static String rangeQuery(HttpServletRequest request) {
        String from = parameter(request, "statsFrom"); String to = parameter(request, "statsTo");
        if (from.isEmpty() && to.isEmpty()) return "";
        if (from.isEmpty() || to.isEmpty()) throw new IllegalArgumentException("请同时填写统计起止日期");
        LocalDate start = date(from); LocalDate end = date(to);
        if (start.getYear() < 1000 || end.equals(LocalDate.of(9999, 12, 31))
                || start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= 366) {
            throw new IllegalArgumentException("统计日期范围须为有效的 1–366 个自然日");
        }
        return "?from=" + from + "&to=" + to;
    }

    static LocalDate date(String value) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException("format");
            return LocalDate.parse(value);
        } catch (DateTimeException failure) { throw new IllegalArgumentException("日期格式不正确，请使用有效的 yyyy-MM-dd 日期"); }
    }

    static String parameter(HttpServletRequest request, String name) {
        String value = request.getParameter(name); return value == null ? "" : value.trim();
    }
}
