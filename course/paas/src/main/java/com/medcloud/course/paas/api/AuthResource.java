package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.contract.RegisterRequest;
import com.medcloud.course.contract.RegisterResponse;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import org.jboss.resteasy.spi.HttpRequest;
import javax.ws.rs.core.Context;

import javax.ejb.EJB;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {
    @EJB
    private AuthService auth;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;

    public AuthResource() { }
    public AuthResource(AuthService auth) { this.auth = auth; }
    public AuthResource(AuthService auth, AuditOperationRunner runner) { this.auth = auth; this.runner = java.util.Objects.requireNonNull(runner); }

    @POST
    @Path("login")
    @AuditTracked
    public ApiResponse<LoginResponse> login(LoginRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.LOGIN, AuditRequestIds.ensure(requestContext));
        LoginResponse result = java.util.Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> auth.login(request, c));
        return ApiResponse.success("logged in", result);
    }

    @POST
    @Path("register")
    public ApiResponse<RegisterResponse> register(RegisterRequest request) {
        return ApiResponse.success("registered", auth.register(request));
    }
}
