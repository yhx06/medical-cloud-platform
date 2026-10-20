package com.medcloud.course.paas.api;

import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import org.jboss.resteasy.spi.HttpRequest;
import javax.ws.rs.core.Context;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.PaymentOrderView;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
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
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;

    public PaymentResource() { }
    public PaymentResource(AuthService auth, PaymentService payments, AuditOperationRunner runner) {
        this(auth,payments); this.runner=java.util.Objects.requireNonNull(runner);
    }
    public PaymentResource(AuthService auth, PaymentService payments) {
        this.auth = auth;
        this.payments = payments;
    }

    @GET
    @Path("patient/appointments/{id}/order")
    public ApiResponse<PaymentOrderView> order(@HeaderParam("Authorization") String authorization,
                                               @PathParam("id") String appointmentId) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        PaymentOrder order = payments.forPatient(actor, appointmentId);
        return ApiResponse.success("order", view(order, payments.businessWritable(actor, order.getId())));
    }

    @POST @AuditTracked
    @Path("patient/orders/{id}/pay")
    public ApiResponse<PaymentOrderView> pay(@HeaderParam("Authorization") String authorization,
                                             @PathParam("id") String orderId) {
        AuditContext context=AuditContext.create(AuditEventType.PAYMENT_PAY,AuditRequestIds.ensure(requestContext));
        PaymentOrder order=java.util.Objects.requireNonNull(runner,"Audit runner required").execute(context,c -> {
            CurrentActor actor=auth.authenticate(BearerTokens.require(authorization)); c.attachActor(actor);
            return payments.pay(actor,orderId,c);
        });
        CurrentActor actor=new CurrentActor(context.getActorId(),context.getActorRole(),null);
        return ApiResponse.success("paid", view(order, payments.businessWritable(actor, order.getId())));
    }

    private static PaymentOrderView view(PaymentOrder order, boolean businessWritable) {
        return new PaymentOrderView(order.getId(), order.getAppointmentId(), order.getAmount(), order.getStatus(),
                order.getPaidAt(), com.medcloud.course.paas.order.PaymentReference.masked(order.getPaymentReference()),
                businessWritable);
    }
}
