package com.medcloud.course.paas.api;

import com.medcloud.course.paas.audit.AuditContext;
import javax.ws.rs.container.ContainerRequestContext;
import org.jboss.resteasy.spi.HttpRequest;
import java.util.UUID;

public final class AuditRequestIds {
    private static final String PROPERTY = AuditRequestIds.class.getName() + ".serverRequestId";
    private AuditRequestIds() {}
    public static String ensure(ContainerRequestContext request) {
        if (request == null) return UUID.randomUUID().toString();
        String existing = get(request);
        if (existing != null) return existing;
        String id = UUID.randomUUID().toString(); request.setProperty(PROPERTY, id); return id;
    }
    public static String get(ContainerRequestContext request) {
        Object value = request == null ? null : request.getProperty(PROPERTY);
        return value == null ? null : AuditContext.requireRequestId((String) value);
    }
    /** RESTEasy exposes HttpRequest to resources/mappers; filter properties use its same attributes. */
    public static String ensure(HttpRequest request) {
        if (request == null) return UUID.randomUUID().toString();
        String existing = get(request);
        if (existing != null) return existing;
        String id = UUID.randomUUID().toString(); request.setAttribute(PROPERTY, id); return id;
    }
    public static String get(HttpRequest request) {
        Object value = request == null ? null : request.getAttribute(PROPERTY);
        return value == null ? null : AuditContext.requireRequestId((String) value);
    }
}
