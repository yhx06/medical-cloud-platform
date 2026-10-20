package com.medcloud.course.paas.api;

import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import org.jboss.resteasy.spi.HttpRequest;
import javax.ws.rs.core.Context;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.AppointmentView;
import com.medcloud.course.contract.AppointmentHistoryView;
import com.medcloud.course.contract.AppointmentHistoryPage;
import com.medcloud.course.contract.AppointmentPage;
import com.medcloud.course.contract.BookingRequest;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.PageResult;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;

import javax.ejb.EJB;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.core.MediaType;
import java.sql.Date;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppointmentResource {
    @EJB
    private AuthService auth;
    @EJB
    private BookingService booking;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;

    public AppointmentResource() { }
    public AppointmentResource(AuthService auth, BookingService booking, AuditOperationRunner runner) {
        this(auth,booking); this.runner=java.util.Objects.requireNonNull(runner);
    }
    public AppointmentResource(AuthService auth, BookingService booking) {
        this.auth = auth;
        this.booking = booking;
    }

    @POST @AuditTracked
    @Path("patient/appointments")
    public ApiResponse<AppointmentView> book(@HeaderParam("Authorization") String authorization,
                                              BookingRequest request) {
        AuditContext context=AuditContext.create(AuditEventType.APPOINTMENT_BOOK,AuditRequestIds.ensure(requestContext));
        Appointment result=java.util.Objects.requireNonNull(runner,"Audit runner required").execute(context,c -> {
            CurrentActor actor=auth.authenticate(BearerTokens.require(authorization)); c.attachActor(actor);
            return booking.book(actor,request==null ? null : request.getSlotId(),
                    Date.valueOf(LocalDate.now(ZoneId.of("Asia/Shanghai"))),c);
        });
        return ApiResponse.success("booked",view(result));
    }

    @GET
    @Path("patient/appointments")
    public ApiResponse<AppointmentHistoryPage> forPatient(@HeaderParam("Authorization") String authorization,
            @QueryParam("page") @DefaultValue("1") String rawPage,
            @QueryParam("size") @DefaultValue("20") String rawSize) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        int page = parsePositiveInt(rawPage, "1");
        int size = parsePositiveInt(rawSize, "20");
        return ApiResponse.success("appointments", booking.forPatient(actor, page, size));
    }

    @POST @AuditTracked
    @Path("patient/appointments/{id}/cancel")
    public ApiResponse<AppointmentView> cancel(@HeaderParam("Authorization") String authorization,
            @PathParam("id") String appointmentId) {
        AuditContext context=AuditContext.create(AuditEventType.APPOINTMENT_CANCEL,AuditRequestIds.ensure(requestContext));
        Appointment result=java.util.Objects.requireNonNull(runner,"Audit runner required").execute(context,c -> {
            CurrentActor actor=auth.authenticate(BearerTokens.require(authorization)); c.attachActor(actor);
            return booking.cancel(actor,appointmentId,Instant.now(),c);
        });
        return ApiResponse.success("canceled",view(result));
    }

    @GET
    @Path("doctor/appointments")
    public ApiResponse<AppointmentPage> forDoctor(@HeaderParam("Authorization") String authorization,
            @QueryParam("page") @DefaultValue("1") String rawPage,
            @QueryParam("size") @DefaultValue("20") String rawSize) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        int page = parsePositiveInt(rawPage, "1");
        int size = parsePositiveInt(rawSize, "20");
        PageResult<Appointment> result = booking.forDoctor(actor, page, size);
        List<AppointmentView> views = new ArrayList<AppointmentView>();
        for (Appointment appointment : result.getItems()) views.add(view(appointment));
        return ApiResponse.success("appointments", new AppointmentPage(views, result.getTotal(), page, size));
    }

    private static AppointmentView view(Appointment appointment) {
        return new AppointmentView(appointment.getId(), appointment.getPatientId(),
                appointment.getSlotId(), appointment.getTenantId(), appointment.getDoctorId(),
                appointment.getFee(), appointment.getStatus());
    }

    private static int parsePositiveInt(String raw, String fallback) {
        String value = (raw == null || raw.trim().isEmpty()) ? fallback : raw.trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid pagination");
        }
    }
}
