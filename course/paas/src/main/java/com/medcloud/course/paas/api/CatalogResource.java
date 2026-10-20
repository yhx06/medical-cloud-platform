package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.SlotView;
import com.medcloud.course.contract.SlotPage;
import com.medcloud.course.contract.CatalogOption;
import com.medcloud.course.paas.PageResult;
import com.medcloud.course.paas.schedule.DirectoryService;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.schedule.SlotAvailability;

import javax.ejb.EJB;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.DefaultValue;
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
    @Path("hospitals")
    public ApiResponse<List<CatalogOption>> hospitals() {
        return ApiResponse.success("hospitals", directory.hospitals());
    }

    @GET
    @Path("departments")
    public ApiResponse<List<CatalogOption>> departments(@QueryParam("tenantId") String tenantId) {
        return ApiResponse.success("departments", directory.departments(tenantId));
    }

    @GET
    @Path("doctors")
    public ApiResponse<List<CatalogOption>> doctors(@QueryParam("tenantId") String tenantId,
            @QueryParam("departmentId") String departmentId) {
        return ApiResponse.success("doctors", directory.doctors(tenantId, departmentId));
    }

    @GET
    @Path("slots")
    public ApiResponse<SlotPage> slots(@QueryParam("tenantId") String tenantId,
            @QueryParam("departmentId") String departmentId, @QueryParam("doctorId") String doctorId,
            @QueryParam("date") String date,
            @QueryParam("page") @DefaultValue("1") String rawPage,
            @QueryParam("size") @DefaultValue("100") String rawSize) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId required");
        }
        int page = parsePositiveInt(rawPage, "1");
        int size = parsePositiveInt(rawSize, "100");
        Date requestedDate = null;
        if (date != null && !date.trim().isEmpty()) {
            try { requestedDate = Date.valueOf(LocalDate.parse(date.trim())); }
            catch (DateTimeParseException | IllegalArgumentException failure) { throw new IllegalArgumentException("invalid date"); }
        }
        PageResult<SlotAvailability> result = directory.availableSlots(tenantId, departmentId, doctorId,
                requestedDate, Date.valueOf(LocalDate.now(ZoneId.of("Asia/Shanghai"))), page, size);
        List<SlotView> views = new ArrayList<SlotView>();
        for (SlotAvailability availability : result.getItems()) {
            ScheduleSlot slot = availability.getSlot();
            views.add(new SlotView(slot.getId(), slot.getTenantId(), slot.getDepartmentId(),
                    slot.getDoctorId(), slot.getDate(), slot.getStartTime(), slot.getEndTime(),
                    slot.getCapacity(), slot.getFee(), availability.getRemaining()));
        }
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
}
