package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.LoginRequest;
import com.medcloud.course.contract.LoginResponse;
import com.medcloud.course.paas.auth.AuthService;

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

    public AuthResource() { }
    public AuthResource(AuthService auth) { this.auth = auth; }

    @POST
    @Path("login")
    public ApiResponse<LoginResponse> login(LoginRequest request) {
        return ApiResponse.success("logged in", auth.login(request));
    }
}
