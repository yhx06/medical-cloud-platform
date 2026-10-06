package com.medcloud.course.paas.api;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import javax.ejb.EJB;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import org.jboss.resteasy.spi.HttpRequest;

@Path("platform/audit-events")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {
    @EJB private AuthService auth;
    @EJB private AuditQueryService audit;
    public AuditResource() {}
    public AuditResource(AuthService auth,AuditQueryService audit){this.auth=auth;this.audit=audit;}
    @GET @AuditTracked
    public ApiResponse<AuditPage> platform(@HeaderParam("Authorization") String authorization,@Context UriInfo uri,@Context HttpRequest request) {
        AuditRequestIds.ensure(request);
        CurrentActor actor=auth.authenticateAuditReader(BearerTokens.require(authorization));
        audit.requireReader(actor,true);
        return ApiResponse.success("audit events",audit.platform(actor,AuditQuery.parse(uri.getQueryParameters(),true)));
    }
}
