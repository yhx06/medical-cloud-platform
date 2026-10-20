package com.medcloud.course.paas.api;

import com.medcloud.course.paas.audit.*;
import com.medcloud.course.contract.AuditEventType;
import com.medcloud.course.paas.auth.CurrentActor;
import org.jboss.resteasy.spi.HttpRequest;
import javax.ws.rs.core.Context;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.ClinicalRecordView;
import com.medcloud.course.contract.ClinicalRecordPage;
import com.medcloud.course.contract.CompleteConsultationRequest;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.PageResult;
import com.medcloud.course.paas.consultation.ClinicalRecord;
import com.medcloud.course.paas.consultation.ClinicalService;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClinicalResource {
    @EJB
    private AuthService auth;
    @EJB
    private ClinicalService clinical;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;

    public ClinicalResource() { }
    public ClinicalResource(AuthService auth, ClinicalService clinical, AuditOperationRunner runner) {
        this(auth,clinical); this.runner=java.util.Objects.requireNonNull(runner);
    }
    public ClinicalResource(AuthService auth, ClinicalService clinical) {
        this.auth = auth;
        this.clinical = clinical;
    }

    @POST @AuditTracked
    @Path("doctor/appointments/{id}/start")
    public ApiResponse<String> start(@HeaderParam("Authorization") String authorization,
                                     @PathParam("id") String appointmentId) {
        AuditContext context=AuditContext.create(AuditEventType.CONSULTATION_START,AuditRequestIds.ensure(requestContext));
        java.util.Objects.requireNonNull(runner,"Audit runner required").execute(context,c -> {
            CurrentActor actor=auth.authenticate(BearerTokens.require(authorization)); c.attachActor(actor);
            return clinical.start(actor,appointmentId,Instant.now(),c);
        });
        return ApiResponse.success("consultation started", appointmentId);
    }

    @POST @AuditTracked
    @Path("doctor/appointments/{id}/complete")
    public ApiResponse<ClinicalRecordView> complete(@HeaderParam("Authorization") String authorization,
                                                     @PathParam("id") String appointmentId,
                                                     CompleteConsultationRequest request) {
        AuditContext context=AuditContext.create(AuditEventType.CONSULTATION_COMPLETE,AuditRequestIds.ensure(requestContext));
        ClinicalRecord record=java.util.Objects.requireNonNull(runner,"Audit runner required").execute(context,c -> {
            CurrentActor actor=auth.authenticate(BearerTokens.require(authorization)); c.attachActor(actor);
            return clinical.complete(actor,appointmentId,request==null ? null : request.getComplaint(),
                    request==null ? null : request.getDiagnosis(),request==null ? null : request.getAdvice(),
                    request==null ? null : request.getNotes(),Instant.now(),c,
                    request==null ? null : request.getExpectedDraftRevision());
        });
        return ApiResponse.success("consultation completed", view(record));
    }

    @GET
    @Path("patient/appointments/{id}/record")
    public ApiResponse<ClinicalRecordView> record(@HeaderParam("Authorization") String authorization,
                                                   @PathParam("id") String appointmentId) {
        return ApiResponse.success("record", view(clinical.forPatient(
                auth.authenticate(BearerTokens.require(authorization)), appointmentId)));
    }

    @GET
    @Path("doctor/records")
    public ApiResponse<ClinicalRecordPage> records(@HeaderParam("Authorization") String authorization,
            @QueryParam("from") String from, @QueryParam("to") String to,
            @QueryParam("page") @DefaultValue("1") String rawPage,
            @QueryParam("size") @DefaultValue("20") String rawSize) {
        Date fromDate = parseDate(from);
        Date toDate = parseDate(to);
        int page = parsePositiveInt(rawPage, "1");
        int size = parsePositiveInt(rawSize, "20");
        PageResult<ClinicalRecord> result = clinical.forDoctor(
                auth.authenticate(BearerTokens.require(authorization)), fromDate, toDate, page, size);
        List<ClinicalRecordView> views = new ArrayList<ClinicalRecordView>();
        for (ClinicalRecord record : result.getItems()) views.add(view(record));
        return ApiResponse.success("records", new ClinicalRecordPage(views, result.getTotal(), page, size));
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

    private static ClinicalRecordView view(ClinicalRecord record) {
        return new ClinicalRecordView(record.getId(), record.getAppointmentId(), record.getDoctorId(),
                record.getPatientId(), record.getComplaint(), record.getDiagnosis(), record.getAdvice(),
                record.getNotes(), record.getCompletedAt());
    }
}
