package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.DoctorProfileRequest;
import com.medcloud.course.contract.DoctorProfileView;
import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.audit.AuditOperationRunner;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.HospitalProfileService;
import org.jboss.resteasy.spi.HttpRequest;

import javax.ejb.EJB;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

@Path("doctor/profile")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DoctorProfileResource {
    @EJB private AuthService auth;
    @EJB private HospitalProfileService profiles;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;
    public DoctorProfileResource() { }
    public DoctorProfileResource(AuthService auth, HospitalProfileService profiles, AuditOperationRunner runner) {
        this.auth = auth; this.profiles = profiles; this.runner = java.util.Objects.requireNonNull(runner);
    }

    @GET
    public ApiResponse<DoctorProfileView> profile(@HeaderParam("Authorization") String authorization) {
        return ApiResponse.success("doctor profile", profiles.doctorProfile(actor(authorization)));
    }

    @PUT @AuditTracked
    public ApiResponse<DoctorProfileView> update(@HeaderParam("Authorization") String authorization, DoctorProfileRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.DOCTOR_PROFILE_UPDATE, AuditRequestIds.ensure(requestContext));
        DoctorProfileView view = runner.execute(context, c -> {
            CurrentActor current = actor(authorization); c.attachActor(current);
            return profiles.updateDoctorProfile(current, request == null ? null : request.getTitle(),
                    request == null ? null : request.getBio(), c);
        });
        return ApiResponse.success("doctor profile", view);
    }

    private CurrentActor actor(String authorization) { return auth.authenticate(BearerTokens.require(authorization)); }
}
