package com.medcloud.course.paas.probe;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.sdk.internal.minio.MinioClient;
import org.apache.pdfbox.pdmodel.PDDocument;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Test-only servlet: never packaged by the product WAR build. */
@WebServlet("/probe")
public final class SdkProbeServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json"); response.setHeader("Cache-Control", "no-store");
        if (!"1.8".equals(System.getProperty("java.specification.version"))) throw new IllegalStateException("Java8 required");
        MinioClient client=MinioClient.builder().endpoint("http://127.0.0.1:1").credentials("probe-access", "probe-secret-unused").build();
        if (client == null) throw new IllegalStateException("SDK unavailable");
        try (PDDocument pdf=new PDDocument()) {
            new ObjectMapper().writeValue(response.getOutputStream(), new AttachmentView("probe-file", AttachmentSourceType.PATIENT_APPOINTMENT, "probe.pdf", "application/pdf", 1, "2026-10-12T12:00:00+08:00"));
        }
    }
}
