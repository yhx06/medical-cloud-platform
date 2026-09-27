package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.SlotView;
import com.medcloud.course.paas.schedule.DirectoryService;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.schedule.SlotAvailability;

import javax.ejb.EJB;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Path("catalog")
@Produces(MediaType.APPLICATION_JSON)
public class CatalogResource {
    @EJB
    private DirectoryService directory;

    public CatalogResource() { }
    public CatalogResource(DirectoryService directory) { this.directory = directory; }

    @GET
    @Path("slots")
    public ApiResponse<List<SlotView>> slots(@QueryParam("tenantId") String tenantId,
            @QueryParam("departmentId") String departmentId, @QueryParam("doctorId") String doctorId,
            @QueryParam("date") String date) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId required");
        }
        List<SlotView> views = new ArrayList<SlotView>();
        Date requestedDate = null;
        if (date != null && !date.trim().isEmpty()) {
            try { requestedDate = Date.valueOf(LocalDate.parse(date.trim())); }
            catch (DateTimeParseException | IllegalArgumentException failure) { throw new IllegalArgumentException("invalid date"); }
        }
        for (SlotAvailability availability : directory.availableSlots(tenantId, departmentId, doctorId,
                requestedDate, Date.valueOf(LocalDate.now(ZoneId.of("Asia/Shanghai"))))) {
            ScheduleSlot slot = availability.getSlot();
            views.add(new SlotView(slot.getId(), slot.getTenantId(), slot.getDepartmentId(),
                    slot.getDoctorId(), slot.getDate(), slot.getStartTime(), slot.getEndTime(),
                    slot.getCapacity(), slot.getFee(), availability.getRemaining()));
        }
        return ApiResponse.success("slots", views);
    }
}
