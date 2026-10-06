package com.medcloud.course.paas.api;

import javax.ws.rs.container.*;
import javax.ws.rs.ext.Provider;

@Provider
@AuditTracked
public class AuditRequestFilter implements ContainerRequestFilter {
    @Override public void filter(ContainerRequestContext request) { AuditRequestIds.ensure(request); }
}
