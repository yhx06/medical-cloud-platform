package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.AuditPage;
import com.medcloud.course.paas.audit.AuditQuery;
import com.medcloud.course.paas.audit.AuditQueryService;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.audit.AuditOperationRunner;
import com.medcloud.course.contract.AuditEventType;
import org.jboss.resteasy.spi.HttpRequest;
import com.medcloud.course.contract.DepartmentRequest;
import com.medcloud.course.contract.DepartmentView;
import com.medcloud.course.contract.DepartmentPage;
import com.medcloud.course.contract.DoctorCreateResponse;
import com.medcloud.course.contract.DoctorRequest;
import com.medcloud.course.contract.DoctorView;
import com.medcloud.course.contract.DoctorPage;
import com.medcloud.course.contract.HospitalNameRequest;
import com.medcloud.course.contract.PaymentOrderView;
import com.medcloud.course.contract.PaymentOrderPage;
import com.medcloud.course.contract.Role;
import com.medcloud.course.contract.TenantContextView;
import com.medcloud.course.contract.StatisticsReport;
import com.medcloud.course.paas.tenant.PlatformTenantService;
import com.medcloud.course.paas.statistics.StatisticsService;
import com.medcloud.course.paas.order.PaymentOrder;
import com.medcloud.course.paas.order.PaymentService;
import com.medcloud.course.paas.PageResult;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.HospitalDepartmentService;
import com.medcloud.course.paas.tenant.HospitalDoctorService;
import com.medcloud.course.paas.tenant.HospitalProfileService;

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
import javax.ws.rs.DefaultValue;
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
    @EJB private HospitalProfileService profiles;
    @EJB private PaymentService payments;
    @EJB private PlatformTenantService tenants;
    @EJB private StatisticsService statistics;
    @EJB private AuditQueryService audit;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;
    public HospitalResource() { }
    public HospitalResource(AuthService auth, PaymentService payments) { this.auth = auth; this.payments = payments; }
    public HospitalResource(AuthService auth, HospitalDepartmentService departments, HospitalDoctorService doctors) {
        this.auth = auth; this.departments = departments; this.doctors = doctors;
    }
    public HospitalResource(AuthService auth, HospitalDepartmentService departments, HospitalDoctorService doctors, AuditOperationRunner runner) {
        this(auth, departments, doctors); this.runner = java.util.Objects.requireNonNull(runner);
    }
    public HospitalResource(AuthService auth, HospitalProfileService profiles, AuditOperationRunner runner) {
        this.auth = auth; this.profiles = profiles; this.runner = java.util.Objects.requireNonNull(runner);
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
    @PUT @Path("name") @AuditTracked
    public ApiResponse<String> rename(@HeaderParam("Authorization") String authorization, HospitalNameRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.HOSPITAL_RENAME, AuditRequestIds.ensure(requestContext));
        String name = java.util.Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = actor(authorization); c.attachActor(current);
            return profiles.renameHospital(current, request == null ? null : request.getName(), c);
        });
        return ApiResponse.success("hospital name", name);
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
    public ApiResponse<PaymentOrderPage> orders(@HeaderParam("Authorization") String authorization,
            @QueryParam("status") String status, @QueryParam("from") String from, @QueryParam("to") String to,
            @QueryParam("page") @DefaultValue("1") String rawPage,
            @QueryParam("size") @DefaultValue("20") String rawSize) {
        int page = parsePositiveInt(rawPage, "1");
        int size = parsePositiveInt(rawSize, "20");
        PageResult<PaymentOrder> result = payments.forHospital(actor(authorization), status,
                parseDate(from), parseDate(to), page, size);
        List<PaymentOrderView> views = new ArrayList<PaymentOrderView>();
        for (PaymentOrder order : result.getItems()) {
            views.add(new PaymentOrderView(order.getId(), order.getAppointmentId(), order.getAmount(),
                    order.getStatus(), order.getPaidAt(),
                    com.medcloud.course.paas.order.PaymentReference.masked(order.getPaymentReference()), false));
        }
        return ApiResponse.success("orders", new PaymentOrderPage(views, result.getTotal(), page, size));
    }

    private static int parsePositiveInt(String raw, String fallback) {
        String value = (raw == null || raw.trim().isEmpty()) ? fallback : raw.trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid pagination");
        }
    }
    private static Date parseDate(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return Date.valueOf(LocalDate.parse(value)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid date"); }
    }

    @GET @Path("departments")
    public ApiResponse<DepartmentPage> departments(@HeaderParam("Authorization") String authorization,
            @QueryParam("page") @DefaultValue("1") String rawPage,
            @QueryParam("size") @DefaultValue("100") String rawSize) {
        int page = parsePositiveInt(rawPage, "1");
        int size = parsePositiveInt(rawSize, "100");
        PageResult<Department> result = departments.list(actor(authorization), page, size);
        List<DepartmentView> views = new ArrayList<DepartmentView>();
        for (Department department : result.getItems()) views.add(view(department));
        return ApiResponse.success("departments", new DepartmentPage(views, result.getTotal(), page, size));
    }
    @POST @Path("departments") @AuditTracked
    public ApiResponse<DepartmentView> createDepartment(@HeaderParam("Authorization") String authorization, DepartmentRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.DEPARTMENT_CREATE, AuditRequestIds.ensure(requestContext));
        Department result = java.util.Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = actor(authorization); c.attachActor(current);
            return departments.create(current, request == null ? null : request.getName(), c);
        });
        return ApiResponse.success("department created", view(result));
    }
    @PUT @Path("departments/{id}") @AuditTracked
    public ApiResponse<DepartmentView> updateDepartment(@HeaderParam("Authorization") String authorization,
            @PathParam("id") String id, DepartmentRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.DEPARTMENT_UPDATE, AuditRequestIds.ensure(requestContext));
        Department result = java.util.Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = actor(authorization); c.attachActor(current);
            return departments.update(current, id, request == null ? null : request.getName(), request == null ? null : request.getStatus(), c);
        });
        return ApiResponse.success("department updated", view(result));
    }
    @GET @Path("doctors")
    public ApiResponse<DoctorPage> doctors(@HeaderParam("Authorization") String authorization,
            @QueryParam("page") @DefaultValue("1") String rawPage,
            @QueryParam("size") @DefaultValue("100") String rawSize) {
        int page = parsePositiveInt(rawPage, "1");
        int size = parsePositiveInt(rawSize, "100");
        PageResult<com.medcloud.course.contract.DoctorView> result = doctors.list(actor(authorization), page, size);
        return ApiResponse.success("doctors", new DoctorPage(result.getItems(), result.getTotal(), page, size));
    }
    @POST @Path("doctors") @AuditTracked
    public ApiResponse<DoctorCreateResponse> createDoctor(@HeaderParam("Authorization") String authorization, DoctorRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.DOCTOR_CREATE, AuditRequestIds.ensure(requestContext));
        DoctorCreateResponse result = java.util.Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = actor(authorization); c.attachActor(current);
            return doctors.create(current, request == null ? null : request.getUsername(),
                    request == null ? null : request.getDisplayName(), request == null ? null : request.getDepartmentId(), c);
        });
        return ApiResponse.success("doctor created", result);
    }
    @PUT @Path("doctors/{id}") @AuditTracked
    public ApiResponse<DoctorView> updateDoctor(@HeaderParam("Authorization") String authorization,
            @PathParam("id") String id, DoctorRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.DOCTOR_UPDATE, AuditRequestIds.ensure(requestContext));
        DoctorView result = java.util.Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = actor(authorization); c.attachActor(current);
            return doctors.update(current, id, request == null ? null : request.getDisplayName(),
                    request == null ? null : request.getDepartmentId(), request == null ? null : request.getStatus(), c);
        });
        return ApiResponse.success("doctor updated", result);
    }
    private static DepartmentView view(Department department) {
        return new DepartmentView(department.getId(), department.getName(), department.getStatus());
    }
}
