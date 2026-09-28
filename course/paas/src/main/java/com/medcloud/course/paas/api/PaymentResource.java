package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.PaymentOrderView;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.order.PaymentOrder;
import com.medcloud.course.paas.order.PaymentService;

import javax.ejb.EJB;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class PaymentResource {
    @EJB
    private AuthService auth;
    @EJB
    private PaymentService payments;

    public PaymentResource() { }
    public PaymentResource(AuthService auth, PaymentService payments) {
        this.auth = auth;
        this.payments = payments;
    }

    @GET
    @Path("patient/appointments/{id}/order")
    public ApiResponse<PaymentOrderView> order(@HeaderParam("Authorization") String authorization,
                                               @PathParam("id") String appointmentId) {
        return ApiResponse.success("order", view(payments.forPatient(
                auth.authenticate(BearerTokens.require(authorization)), appointmentId)));
    }

    @POST
    @Path("patient/orders/{id}/pay")
    public ApiResponse<PaymentOrderView> pay(@HeaderParam("Authorization") String authorization,
                                             @PathParam("id") String orderId) {
        return ApiResponse.success("paid", view(payments.pay(
                auth.authenticate(BearerTokens.require(authorization)), orderId)));
    }

    private static PaymentOrderView view(PaymentOrder order) {
        return new PaymentOrderView(order.getId(), order.getAppointmentId(), order.getAmount(), order.getStatus(),
                order.getPaidAt());
    }
}
