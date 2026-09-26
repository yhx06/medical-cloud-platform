package com.medcloud.course.paas.api;

import com.medcloud.course.contract.ApiResponse;
import com.medcloud.course.contract.ClinicalRecordView;
import com.medcloud.course.contract.CompleteConsultationRequest;
import com.medcloud.course.paas.auth.AuthService;
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
import javax.ws.rs.core.MediaType;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClinicalResource {
    @EJB
    private AuthService auth;
    @EJB
    private ClinicalService clinical;

    public ClinicalResource() { }
    public ClinicalResource(AuthService auth, ClinicalService clinical) {
        this.auth = auth;
        this.clinical = clinical;
    }

    @POST
    @Path("doctor/appointments/{id}/start")
    public ApiResponse<String> start(@HeaderParam("Authorization") String authorization,
                                     @PathParam("id") String appointmentId) {
        clinical.start(auth.authenticate(BearerTokens.require(authorization)), appointmentId);
        return ApiResponse.success("consultation started", appointmentId);
    }

    @POST
    @Path("doctor/appointments/{id}/complete")
    public ApiResponse<ClinicalRecordView> complete(@HeaderParam("Authorization") String authorization,
                                                     @PathParam("id") String appointmentId,
                                                     CompleteConsultationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("record required");
        }
        ClinicalRecord record = clinical.complete(auth.authenticate(BearerTokens.require(authorization)),
                appointmentId, request.getDiagnosis(), request.getAdvice());
        return ApiResponse.success("consultation completed", view(record));
    }

    @GET
    @Path("patient/appointments/{id}/record")
    public ApiResponse<ClinicalRecordView> record(@HeaderParam("Authorization") String authorization,
                                                   @PathParam("id") String appointmentId) {
        return ApiResponse.success("record", view(clinical.forPatient(
                auth.authenticate(BearerTokens.require(authorization)), appointmentId)));
    }

    private static ClinicalRecordView view(ClinicalRecord record) {
        return new ClinicalRecordView(record.getId(), record.getAppointmentId(), record.getDoctorId(),
                record.getPatientId(), record.getDiagnosis(), record.getAdvice());
    }
}
