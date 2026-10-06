package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.AuditPage;
import com.medcloud.course.paas.audit.AuditQuery;
import com.medcloud.course.paas.audit.AuditQueryService;
import org.jboss.resteasy.spi.HttpRequest;
import com.medcloud.course.contract.DepartmentRequest;
import com.medcloud.course.contract.DepartmentView;
import com.medcloud.course.contract.DoctorCreateResponse;
import com.medcloud.course.contract.DoctorRequest;
import com.medcloud.course.contract.DoctorView;
import com.medcloud.course.contract.PaymentOrderView;
import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.TenantContextView;
import com.medcloud.course.contract.StatisticsReport;
import com.medcloud.course.paas.tenant.PlatformTenantService;
import com.medcloud.course.paas.statistics.StatisticsService;
import com.medcloud.course.paas.order.PaymentOrder;
import com.medcloud.course.paas.order.PaymentService;
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
import javax.ws.rs.QueryParam;
import java.sql.Date;
import java.time.LocalDate;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;

@Path("hospital")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HospitalResource {
    @EJB private AuthService auth;
    @EJB private HospitalDepartmentService departments;
    @EJB private HospitalDoctorService doctors;
    @EJB private PaymentService payments;
    @EJB private PlatformTenantService tenants;
    @EJB private StatisticsService statistics;
    @EJB private AuditQueryService audit;
    public HospitalResource() { }
    public HospitalResource(AuthService auth, PaymentService payments) { this.auth = auth; this.payments = payments; }
    public HospitalResource(AuthService auth, HospitalDepartmentService departments, HospitalDoctorService doctors) {
        this.auth = auth; this.departments = departments; this.doctors = doctors;
    }
    public HospitalResource(AuthService auth, PlatformTenantService tenants, StatisticsService statistics) {
        this.auth = auth; this.tenants = tenants; this.statistics = statistics;
    }
    public HospitalResource(AuthService auth, PaymentService payments, PlatformTenantService tenants,
            StatisticsService statistics, AuditQueryService audit) {
        this.auth = auth; this.payments = payments; this.tenants = tenants; this.statistics = statistics; this.audit = audit;
    }
    private CurrentActor actor(String authorization) { return auth.authenticate(BearerTokens.require(authorization)); }

    @GET @Path("audit-events") @AuditTracked
    public ApiResponse<AuditPage> auditEvents(@HeaderParam("Authorization") String authorization,
            @Context UriInfo uri, @Context HttpRequest request) {
        AuditRequestIds.ensure(request);
        CurrentActor current = auth.authenticateAuditReader(BearerTokens.require(authorization));
        audit.requireReader(current, false);
        return ApiResponse.success("audit events", audit.hospital(current, AuditQuery.parse(uri.getQueryParameters(), false)));
    }

    @GET @Path("context")
    public ApiResponse<TenantContextView> context(@HeaderParam("Authorization") String authorization) {
        return ApiResponse.success("hospital context", tenants.context(actor(authorization), Role.HOSPITAL_ADMIN));
    }

    @GET @Path("statistics")
    public ApiResponse<StatisticsReport> statistics(@HeaderParam("Authorization") String authorization,
            @QueryParam("from") String from, @QueryParam("to") String to, @Context UriInfo uri) {
        CurrentActor current = actor(authorization);
        if (uri.getQueryParameters().containsKey("tenantId"))
            throw new IllegalArgumentException("hospital statistics does not accept tenantId");
        return ApiResponse.success("hospital statistics", statistics.hospital(current, from, to));
    }

    @GET @Path("orders")
    public ApiResponse<List<PaymentOrderView>> orders(@HeaderParam("Authorization") String authorization,
            @QueryParam("status") String status, @QueryParam("from") String from, @QueryParam("to") String to) {
        List<PaymentOrderView> views = new ArrayList<PaymentOrderView>();
        for (PaymentOrder order : payments.forHospital(actor(authorization), status, parseDate(from), parseDate(to))) {
            views.add(new PaymentOrderView(order.getId(), order.getAppointmentId(), order.getAmount(),
                    order.getStatus(), order.getPaidAt()));
        }
        return ApiResponse.success("orders", views);
    }
    private static Date parseDate(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return Date.valueOf(LocalDate.parse(value)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid date"); }
    }

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
