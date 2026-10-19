package com.medcloud.course.paas.api;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.*;
import org.jboss.resteasy.spi.HttpRequest;
import javax.ejb.EJB;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.Objects;

@Path("platform/tenants/{tenantId}/administrators")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HospitalAdministratorResource {
    @EJB private AuthService auth;
    @EJB private PlatformAdministrationService administration;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;

    public HospitalAdministratorResource() { }
    public HospitalAdministratorResource(AuthService auth, PlatformAdministrationService administration,
            AuditOperationRunner runner) {
        this.auth = Objects.requireNonNull(auth);
        this.administration = Objects.requireNonNull(administration);
        this.runner = Objects.requireNonNull(runner);
    }

    @GET
    public ApiResponse<HospitalAdministratorPage> list(@HeaderParam("Authorization") String authorization,
            @PathParam("tenantId") String tenantId, @QueryParam("page") @DefaultValue("1") String rawPage,
            @QueryParam("size") @DefaultValue("20") String rawSize) {
        CurrentActor current = auth.authenticate(BearerTokens.require(authorization));
        requirePlatform(current);
        int page, size;
        try {
            page = Integer.parseInt(rawPage == null ? "1" : rawPage);
            size = Integer.parseInt(rawSize == null ? "20" : rawSize);
            HospitalAdministrationRules.requireOffset(page, size);
        } catch (IllegalArgumentException invalid) {
            throw new HospitalAdministrationException(400, "HOSPITAL_ONBOARDING_INVALID");
        }
        return ApiResponse.success("hospital administrators", administration.administrators(current, tenantId, page, size));
    }

    @POST @AuditTracked
    public ApiResponse<HospitalAdminCreationView> create(@HeaderParam("Authorization") String authorization,
            @PathParam("tenantId") String tenantId, HospitalAdministratorCreateRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.HOSPITAL_ADMIN_CREATE, AuditRequestIds.ensure(requestContext));
        HospitalAdminCreationView result = Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = auth.authenticate(BearerTokens.require(authorization)); c.attachActor(current);
            requirePlatform(current);
            return administration.addAdministrator(current, tenantId, request, c);
        });
        return ApiResponse.success("hospital administrator created", result);
    }

    @PUT @Path("{adminId}/status") @AuditTracked
    public ApiResponse<HospitalAdministratorView> changeStatus(@HeaderParam("Authorization") String authorization,
            @PathParam("tenantId") String tenantId, @PathParam("adminId") String adminId, TenantStatusRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.HOSPITAL_ADMIN_STATUS_CHANGE, AuditRequestIds.ensure(requestContext));
        HospitalAdministratorView result = Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = auth.authenticate(BearerTokens.require(authorization)); c.attachActor(current);
            requirePlatform(current);
            return administration.changeAdministratorStatus(current, tenantId, adminId, request, c);
        });
        return ApiResponse.success("hospital administrator status", result);
    }

    private static void requirePlatform(CurrentActor actor) {
        if (actor.getRole() != Role.PLATFORM_ADMIN) throw new AccessDeniedException();
    }
}
