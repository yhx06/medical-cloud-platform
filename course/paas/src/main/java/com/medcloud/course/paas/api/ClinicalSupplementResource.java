package com.medcloud.course.paas.api;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.audit.AuditOperationRunner;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.consultation.ClinicalSupplementService;
import org.jboss.resteasy.spi.HttpRequest;
import javax.ejb.EJB;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.Objects;

/** Clinical supplements retain the service's parent authorization and transaction boundary. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClinicalSupplementResource {
    @EJB private AuthService auth;
    @EJB private ClinicalSupplementService clinical;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;

    public ClinicalSupplementResource() { }
    public ClinicalSupplementResource(AuthService auth,ClinicalSupplementService clinical,AuditOperationRunner runner) {
        this.auth=Objects.requireNonNull(auth);
        this.clinical=Objects.requireNonNull(clinical);
        this.runner=Objects.requireNonNull(runner);
    }
    private CurrentActor actor(String authorization) { return auth.authenticate(BearerTokens.require(authorization)); }
    private <T> T write(String authorization,AuditEventType type,ClinicalWrite<T> action) {
        AuditContext context=AuditContext.create(type,AuditRequestIds.ensure(requestContext));
        return Objects.requireNonNull(runner,"Audit runner required").execute(context,c -> {
            CurrentActor actor=actor(authorization);c.attachActor(actor);
            return action.execute(actor,c);
        });
    }
    @FunctionalInterface private interface ClinicalWrite<T> { T execute(CurrentActor actor,AuditContext context); }

    @GET @Path("patient/appointments/{id}/complaint")
    public ApiResponse<PatientComplaintView> complaint(@HeaderParam("Authorization") String authorization,@PathParam("id") String id) {
        return ApiResponse.success("complaint",clinical.complaint(actor(authorization),id));
    }
    @PUT @AuditTracked @Path("patient/appointments/{id}/complaint")
    public ApiResponse<PatientComplaintView> updateComplaint(@HeaderParam("Authorization") String authorization,@PathParam("id") String id,SupplementComplaintRequest request) {
        return ApiResponse.success("complaint updated",write(authorization,AuditEventType.APPOINTMENT_COMPLAINT_UPDATE,
                (actor,context) -> clinical.updateComplaint(actor,id,request,context)));
    }
    @GET @Path("doctor/appointments/{id}/clinical-workspace")
    public ApiResponse<ClinicalWorkspaceView> workspace(@HeaderParam("Authorization") String authorization,@PathParam("id") String id) {
        return ApiResponse.success("clinical workspace",clinical.workspace(actor(authorization),id));
    }
    @PUT @AuditTracked @Path("doctor/appointments/{id}/draft")
    public ApiResponse<ClinicalDraftView> saveDraft(@HeaderParam("Authorization") String authorization,@PathParam("id") String id,SaveClinicalDraftRequest request) {
        return ApiResponse.success("draft saved",write(authorization,AuditEventType.CONSULTATION_DRAFT_SAVE,
                (actor,context) -> clinical.saveDraft(actor,id,request,context)));
    }
    @GET @Path("doctor/records/{id}")
    public ApiResponse<ClinicalRecordDetailsView> doctorDetails(@HeaderParam("Authorization") String authorization,@PathParam("id") String id) {
        return ApiResponse.success("record details",clinical.doctorDetails(actor(authorization),id));
    }
    @GET @Path("patient/appointments/{id}/record/details")
    public ApiResponse<ClinicalRecordDetailsView> patientDetails(@HeaderParam("Authorization") String authorization,@PathParam("id") String id) {
        return ApiResponse.success("record details",clinical.patientDetails(actor(authorization),id));
    }
    @GET @Path("doctor/records/{id}/corrections")
    public ApiResponse<RecordCorrectionPage> doctorCorrections(@HeaderParam("Authorization") String authorization,@PathParam("id") String id,
            @QueryParam("page") String page,@QueryParam("pageSize") String pageSize) {
        // Preserve raw values: the service authorizes the parent before parsing pagination.
        return ApiResponse.success("record corrections",clinical.correctionsForDoctor(actor(authorization),id,page,pageSize));
    }
    @GET @Path("patient/appointments/{id}/record/corrections")
    public ApiResponse<RecordCorrectionPage> patientCorrections(@HeaderParam("Authorization") String authorization,@PathParam("id") String id,
            @QueryParam("page") String page,@QueryParam("pageSize") String pageSize) {
        return ApiResponse.success("record corrections",clinical.correctionsForPatient(actor(authorization),id,page,pageSize));
    }
    @POST @AuditTracked @Path("doctor/records/{id}/corrections")
    public ApiResponse<RecordCorrectionView> appendCorrection(@HeaderParam("Authorization") String authorization,@PathParam("id") String id,AppendCorrectionRequest request) {
        return ApiResponse.success("record corrected",write(authorization,AuditEventType.RECORD_CORRECTION_APPEND,
                (actor,context) -> clinical.appendCorrection(actor,id,request,context)));
    }
}
