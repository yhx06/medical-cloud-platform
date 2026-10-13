package com.medcloud.course.paas.probe;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.sdk.internal.minio.MinioClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.IOException;

/** Test only: let the actual container JAX-RS JSON provider serialize this response. */
@Path("/sdk-probe")
public final class SdkProbeResource {
    @POST
    @Path("/register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ApiResponse<String> register(RegisterRequest request) throws IOException {
        json();
        return ApiResponse.success("Register probe",request.getUsername());
    }
    @POST
    @Path("/tenant-status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ApiResponse<String> tenantStatus(TenantStatusRequest request) throws IOException {
        json();
        return ApiResponse.success("Tenant probe",request.getStatus());
    }
    @POST
    @Path("/descriptor")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ApiResponse<PrepareUploadRequest> prepare(PrepareUploadRequest request) throws IOException {
        json(); // SDK construction must still work in the same JAX-RS request path.
        return ApiResponse.success("Descriptor probe",request);
    }
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ApiResponse<AttachmentView> json() throws IOException {
        if (!"1.8".equals(System.getProperty("java.specification.version"))) throw new IllegalStateException("Java8 required");
        MinioClient client=MinioClient.builder().endpoint("http://127.0.0.1:1").credentials("probe-access", "probe-secret-unused").build();
        if(client==null) throw new IllegalStateException("SDK unavailable");
        try(PDDocument pdf=new PDDocument()) {
            return ApiResponse.success("SDK probe",new AttachmentView("probe-file",AttachmentSourceType.PATIENT_APPOINTMENT,"probe.pdf","application/pdf",1,"2026-10-12T12:00:00+08:00"));
        }
    }
}
