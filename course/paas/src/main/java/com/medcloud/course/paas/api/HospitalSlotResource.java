package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.SlotRequest;
import com.medcloud.course.contract.SlotView;
import com.medcloud.course.contract.SlotPage;
import com.medcloud.course.paas.PageResult;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.schedule.SlotAdminService;
import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import org.jboss.resteasy.spi.HttpRequest;
import javax.ws.rs.core.Context;

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
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;

@Path("hospital/slots")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HospitalSlotResource {
    @EJB private AuthService auth;
    @EJB private SlotAdminService slots;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;
    public HospitalSlotResource() { }
    public HospitalSlotResource(AuthService auth, SlotAdminService slots) { this.auth = auth; this.slots = slots; }
    public HospitalSlotResource(AuthService auth, SlotAdminService slots, AuditOperationRunner runner) {
        this(auth, slots); this.runner = java.util.Objects.requireNonNull(runner);
    }
    private CurrentActor actor(String authorization) { return auth.authenticate(BearerTokens.require(authorization)); }

    @GET
    public ApiResponse<SlotPage> list(@HeaderParam("Authorization") String authorization,
            @QueryParam("page") @DefaultValue("1") String rawPage,
            @QueryParam("size") @DefaultValue("100") String rawSize) {
        int page = parsePositiveInt(rawPage, "1");
        int size = parsePositiveInt(rawSize, "100");
        PageResult<ScheduleSlot> result = slots.list(actor(authorization), page, size);
        List<SlotView> views = new ArrayList<SlotView>();
        for (ScheduleSlot slot : result.getItems()) views.add(view(slot));
        return ApiResponse.success("slots", new SlotPage(views, result.getTotal(), page, size));
    }

    private static int parsePositiveInt(String raw, String fallback) {
        String value = (raw == null || raw.trim().isEmpty()) ? fallback : raw.trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid pagination");
        }
    }
    @POST @AuditTracked
    public ApiResponse<SlotView> create(@HeaderParam("Authorization") String authorization, SlotRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.SLOT_CREATE, AuditRequestIds.ensure(requestContext));
        ScheduleSlot result = java.util.Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = actor(authorization); c.attachActor(current);
            return slots.create(current, request, c);
        });
        return ApiResponse.success("slot created", view(result));
    }
    @PUT @Path("{id}") @AuditTracked
    public ApiResponse<SlotView> update(@HeaderParam("Authorization") String authorization,
            @PathParam("id") String id, SlotRequest request) {
        AuditContext context = AuditContext.create(AuditEventType.SLOT_UPDATE, AuditRequestIds.ensure(requestContext));
        ScheduleSlot result = java.util.Objects.requireNonNull(runner, "Audit runner required").execute(context, c -> {
            CurrentActor current = actor(authorization); c.attachActor(current);
            return slots.update(current, id, request, c);
        });
        return ApiResponse.success("slot updated", view(result));
    }
    private SlotView view(ScheduleSlot slot) {
        return new SlotView(slot.getId(), slot.getTenantId(), slot.getDepartmentId(), slot.getDoctorId(),
                slot.getDate(), slot.getStartTime(), slot.getEndTime(), slot.getCapacity(), slot.getFee(),
                slots.remaining(slot), slot.getStatus());
    }
}
