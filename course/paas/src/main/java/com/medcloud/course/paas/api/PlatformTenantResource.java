package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.TenantPage;
import com.medcloud.course.contract.TenantStatusRequest;
import com.medcloud.course.contract.TenantView;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.PlatformTenantService;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import org.jboss.resteasy.spi.HttpRequest;
import javax.ws.rs.core.Context;
import javax.ejb.EJB;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

@Path("platform/tenants")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PlatformTenantResource {
    @EJB private AuthService auth;
    @EJB private PlatformTenantService tenants;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;
    public PlatformTenantResource() { }
    public PlatformTenantResource(AuthService auth, PlatformTenantService tenants) { this.auth = auth; this.tenants = tenants; }
    public PlatformTenantResource(AuthService auth, PlatformTenantService tenants, AuditOperationRunner runner) {
        this(auth, tenants); this.runner = java.util.Objects.requireNonNull(runner);
    }
    @GET
    public ApiResponse<TenantPage> list(@HeaderParam("Authorization") String authorization,
            @QueryParam("page") @DefaultValue("1") String rawPage, @QueryParam("size") @DefaultValue("20") String rawSize) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        if (actor.getRole() != Role.PLATFORM_ADMIN) throw new AccessDeniedException();
        // Parse only after authentication/role checks, never during RESTEasy parameter injection.
        int page = Integer.parseInt(rawPage == null ? "1" : rawPage);
        int size = Integer.parseInt(rawSize == null ? "20" : rawSize);
        if (page < 1 || size < 1 || size > 100 || ((long) page - 1) * size > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid pagination");
        return ApiResponse.success("hospitals", tenants.list(actor, page, size));
    }
    @PUT @Path("{id}/status") @AuditTracked
    public ApiResponse<TenantView> changeStatus(@HeaderParam("Authorization") String authorization,
            @PathParam("id") String id, TenantStatusRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.TENANT_STATUS_CHANGE, AuditRequestIds.ensure(requestContext));
        TenantView result = java.util.Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = auth.authenticate(BearerTokens.require(authorization)); c.attachActor(current);
            return tenants.changeStatus(current, id, request, c);
        });
        return ApiResponse.success("hospital status", result);
    }
}
