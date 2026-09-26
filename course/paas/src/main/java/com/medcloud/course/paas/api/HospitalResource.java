package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.DepartmentRequest;
import com.medcloud.course.contract.DepartmentView;
import com.medcloud.course.contract.DoctorCreateResponse;
import com.medcloud.course.contract.DoctorRequest;
import com.medcloud.course.contract.DoctorView;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.HospitalDepartmentService;
import com.medcloud.course.paas.tenant.HospitalDoctorService;

import javax.ejb.EJB;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;

@Path("hospital")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HospitalResource {
    @EJB private AuthService auth;
    @EJB private HospitalDepartmentService departments;
    @EJB private HospitalDoctorService doctors;
    public HospitalResource() { }
    public HospitalResource(AuthService auth, HospitalDepartmentService departments, HospitalDoctorService doctors) {
        this.auth = auth; this.departments = departments; this.doctors = doctors;
    }
    private CurrentActor actor(String authorization) { return auth.authenticate(BearerTokens.require(authorization)); }

    @GET @Path("departments")
    public ApiResponse<List<DepartmentView>> departments(@HeaderParam("Authorization") String authorization) {
        List<DepartmentView> result = new ArrayList<DepartmentView>();
        for (Department department : departments.list(actor(authorization))) result.add(view(department));
        return ApiResponse.success("departments", result);
    }
    @POST @Path("departments")
    public ApiResponse<DepartmentView> createDepartment(@HeaderParam("Authorization") String authorization, DepartmentRequest request) {
        if (request == null) throw new IllegalArgumentException("request required");
        return ApiResponse.success("department created", view(departments.create(actor(authorization), request.getName())));
    }
    @PUT @Path("departments/{id}")
    public ApiResponse<DepartmentView> updateDepartment(@HeaderParam("Authorization") String authorization,
            @PathParam("id") String id, DepartmentRequest request) {
        if (request == null) throw new IllegalArgumentException("request required");
        return ApiResponse.success("department updated", view(departments.update(actor(authorization), id,
                request.getName(), request.getStatus())));
    }
    @GET @Path("doctors")
    public ApiResponse<List<DoctorView>> doctors(@HeaderParam("Authorization") String authorization) {
        return ApiResponse.success("doctors", doctors.list(actor(authorization)));
    }
    @POST @Path("doctors")
    public ApiResponse<DoctorCreateResponse> createDoctor(@HeaderParam("Authorization") String authorization, DoctorRequest request) {
        if (request == null) throw new IllegalArgumentException("request required");
        return ApiResponse.success("doctor created", doctors.create(actor(authorization), request.getUsername(),
                request.getDisplayName(), request.getDepartmentId()));
    }
    @PUT @Path("doctors/{id}")
    public ApiResponse<DoctorView> updateDoctor(@HeaderParam("Authorization") String authorization,
            @PathParam("id") String id, DoctorRequest request) {
        if (request == null) throw new IllegalArgumentException("request required");
        return ApiResponse.success("doctor updated", doctors.update(actor(authorization), id,
                request.getDisplayName(), request.getDepartmentId(), request.getStatus()));
    }
    private static DepartmentView view(Department department) {
        return new DepartmentView(department.getId(), department.getName(), department.getStatus());
    }
}
