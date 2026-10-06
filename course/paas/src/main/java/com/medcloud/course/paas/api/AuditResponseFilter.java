package com.medcloud.course.paas.api;

import javax.ws.rs.container.*;
import javax.ws.rs.ext.Provider;

@Provider
@AuditTracked
public class AuditResponseFilter implements ContainerResponseFilter {
    @Override public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("X-Request-ID", AuditRequestIds.ensure(request));
    }
}
