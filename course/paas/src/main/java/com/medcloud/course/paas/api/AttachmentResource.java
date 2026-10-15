package com.medcloud.course.paas.api;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.attachment.AttachmentCoordinator;
import com.medcloud.course.paas.attachment.AttachmentDatabaseService;
import com.medcloud.course.paas.audit.AuditContext;
import com.medcloud.course.paas.audit.AuditOperationRunner;
import com.medcloud.course.paas.auth.AuthService;
import com.medcloud.course.paas.auth.CurrentActor;
import org.jboss.resteasy.spi.HttpRequest;

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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.util.Objects;

/**
 * The eight attachment routes. Authentication and parent-resource authorization run before
 * pagination parsing or content reception; success entities are ApiResponse, failures stay
 * on the closed ApiError vocabulary. No route exposes object keys or admin download entry.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class AttachmentResource {
    @EJB private AuthService auth;
    @EJB private AttachmentDatabaseService attachments;
    @EJB private AttachmentCoordinator coordinator;
    @EJB private AuditOperationRunner runner;
    @Context private HttpRequest requestContext;

    public AttachmentResource() { }

    public AttachmentResource(AuthService auth, AttachmentDatabaseService attachments,
                              AttachmentCoordinator coordinator, AuditOperationRunner runner) {
        this.auth = Objects.requireNonNull(auth);
        this.attachments = Objects.requireNonNull(attachments);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.runner = Objects.requireNonNull(runner);
    }

    @GET
    @Path("patient/appointments/{id}/files")
    public Response listPatientFiles(@HeaderParam("Authorization") String authorization,
                                     @PathParam("id") String appointmentId,
                                     @QueryParam("page") String page,
                                     @QueryParam("pageSize") String pageSize) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        return ok("files", attachments.listForPatient(actor, appointmentId, page, pageSize));
    }

    @GET
    @Path("doctor/appointments/{id}/files")
    public Response listDoctorFiles(@HeaderParam("Authorization") String authorization,
                                    @PathParam("id") String appointmentId,
                                    @QueryParam("page") String page,
                                    @QueryParam("pageSize") String pageSize) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        return ok("files", attachments.listForDoctor(actor, appointmentId, page, pageSize));
    }

    @POST
    @AuditTracked
    @Path("patient/appointments/{id}/files/uploads")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response preparePatientUpload(@HeaderParam("Authorization") String authorization,
                                         @PathParam("id") String appointmentId,
                                         PrepareUploadRequest descriptor) {
        AuditContext context = AuditContext.create(AuditEventType.FILE_UPLOAD, AuditRequestIds.ensure(requestContext));
        UploadOperationView view = runner.execute(context, c -> {
            CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
            c.attachActor(actor);
            return attachments.preparePatient(actor, appointmentId, descriptor, java.time.Instant.now());
        });
        return ok("upload", view);
    }

    @POST
    @AuditTracked
    @Path("doctor/records/{id}/files/uploads")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response prepareDoctorUpload(@HeaderParam("Authorization") String authorization,
                                        @PathParam("id") String recordId,
                                        PrepareUploadRequest descriptor) {
        AuditContext context = AuditContext.create(AuditEventType.FILE_UPLOAD, AuditRequestIds.ensure(requestContext));
        UploadOperationView view = runner.execute(context, c -> {
            CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
            c.attachActor(actor);
            return attachments.prepareDoctor(actor, recordId, descriptor, java.time.Instant.now());
        });
        return ok("upload", view);
    }

    @PUT
    @AuditTracked
    @Path("files/uploads/{uploadId}/content")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response uploadContent(@HeaderParam("Authorization") String authorization,
                                  @PathParam("uploadId") String uploadId,
                                  InputStream content,
                                  @HeaderParam("Content-Length") Long contentLength) {
        AuditContext context = AuditContext.create(AuditEventType.FILE_UPLOAD, AuditRequestIds.ensure(requestContext));
        UploadOperationView view = coordinator.upload(BearerTokens.require(authorization), uploadId, content, contentLength, context);
        return ok("upload", view);
    }

    @GET
    @Path("files/uploads/{uploadId}")
    public Response uploadOperation(@HeaderParam("Authorization") String authorization,
                                    @PathParam("uploadId") String uploadId) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        return ok("operation", attachments.operation(actor, uploadId));
    }

    @POST
    @AuditTracked
    @Path("patient/files/{fileId}/delete")
    public Response deletePatientFile(@HeaderParam("Authorization") String authorization,
                                      @PathParam("fileId") String fileId) {
        AuditContext context = AuditContext.create(AuditEventType.FILE_DELETE, AuditRequestIds.ensure(requestContext));
        AttachmentDeleteView view = coordinator.deletePatient(BearerTokens.require(authorization), fileId, context);
        int status = view.getState() == AttachmentState.DELETED ? 200 : 202;
        return Response.status(status).type(MediaType.APPLICATION_JSON)
            .entity(ApiResponse.success("deleted", view)).build();
    }

    @POST
    @AuditTracked
    @Path("files/{fileId}/download-url")
    public Response downloadUrl(@HeaderParam("Authorization") String authorization,
                                @PathParam("fileId") String fileId) {
        AuditContext context = AuditContext.create(AuditEventType.FILE_DOWNLOAD_AUTHORIZE, AuditRequestIds.ensure(requestContext));
        DownloadAuthorizationView view = coordinator.authorizeDownload(BearerTokens.require(authorization), fileId, context);
        return Response.ok(ApiResponse.success("download", view)).type(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store").build();
    }

    private static Response ok(String message, Object body) {
        return Response.ok(ApiResponse.success(message, body)).type(MediaType.APPLICATION_JSON).build();
    }
}
