package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.AppointmentView;
import com.medcloud.course.contract.BookingRequest;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;

import javax.ejb.EJB;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.sql.Date;
import java.time.LocalDate;
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

    public AppointmentResource() { }
    public AppointmentResource(AuthService auth, BookingService booking) {
        this.auth = auth;
        this.booking = booking;
    }

    @POST
    @Path("patient/appointments")
    public ApiResponse<AppointmentView> book(@HeaderParam("Authorization") String authorization,
                                              BookingRequest request) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        if (request == null || request.getSlotId() == null || request.getSlotId().trim().isEmpty()) {
            throw new IllegalArgumentException("slotId required");
        }
        return ApiResponse.success("booked", view(booking.book(actor, request.getSlotId(),
                Date.valueOf(LocalDate.now()))));
    }

    @GET
    @Path("doctor/appointments")
    public ApiResponse<List<AppointmentView>> forDoctor(@HeaderParam("Authorization") String authorization) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        List<AppointmentView> views = new ArrayList<AppointmentView>();
        for (Appointment appointment : booking.forDoctor(actor)) {
            views.add(view(appointment));
        }
        return ApiResponse.success("appointments", views);
    }

    private static AppointmentView view(Appointment appointment) {
        return new AppointmentView(appointment.getId(), appointment.getPatientId(),
                appointment.getSlotId(), appointment.getTenantId(), appointment.getDoctorId(),
                appointment.getFee(), appointment.getStatus());
    }
}
