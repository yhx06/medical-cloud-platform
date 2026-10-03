package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.TenantContextView;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.tenant.PlatformTenantService;
import javax.ejb.EJB;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("doctor/context")
@Produces(MediaType.APPLICATION_JSON)
public class TenantContextResource {
    @EJB private AuthService auth;
    @EJB private PlatformTenantService tenants;
    public TenantContextResource() { }
    public TenantContextResource(AuthService auth, PlatformTenantService tenants) { this.auth = auth; this.tenants = tenants; }
    @GET
    public ApiResponse<TenantContextView> doctor(@HeaderParam("Authorization") String authorization) {
        return ApiResponse.success("doctor context", tenants.context(auth.authenticate(BearerTokens.require(authorization)), Role.DOCTOR));
    }
}
