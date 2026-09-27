package com.medcloud.course.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPaymentJspTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void legacyPaidOrderShowsUnknownTimeOnHospitalAndPatientPages() throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("course-jsp-test-").toFile().getAbsolutePath());
        tomcat.setPort(0);
        File webapp = new File("src/main/webapp").getAbsoluteFile();
        Context context = tomcat.addWebapp("/portal", webapp.getAbsolutePath());
        context.setParentClassLoader(getClass().getClassLoader());
        Tomcat.addServlet(context, "legacy-fixture", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
                    throws ServletException, java.io.IOException {
                request.setAttribute("portalTitle", "医疗云");
                request.setAttribute("username", "tester");
                request.setAttribute("csrfToken", "csrf");
                if ("/render-hospital".equals(request.getServletPath())) {
                    request.setAttribute("orders", JSON.readTree("[{\"id\":\"legacy-order\","
                            + "\"appointmentId\":\"legacy-appointment\",\"amount\":39.00,"
                            + "\"status\":\"PAID\",\"paidAt\":null}]"));
                    request.getRequestDispatcher("/WEB-INF/views/hospital.jsp").forward(request, response);
                } else {
                    request.setAttribute("order", JSON.readTree("{\"id\":\"legacy-order\","
                            + "\"amount\":39.00,\"status\":\"PAID\",\"paidAt\":null}"));
                    request.getRequestDispatcher("/WEB-INF/views/patient.jsp").forward(request, response);
                }
            }
        });
        context.addServletMappingDecoded("/render-hospital", "legacy-fixture");
        context.addServletMappingDecoded("/render-patient", "legacy-fixture");
        try {
            tomcat.start();
            int port = tomcat.getConnector().getLocalPort();
            assertTrue(render(port, "/portal/render-hospital").contains("<td>历史时间未知</td>"));
            assertTrue(render(port, "/portal/render-patient").contains("<p>缴费时间：历史时间未知</p>"));
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static String render(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path)
                .openConnection();
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new AssertionError("JSP response status " + status);
            return new String(readAll(connection), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(HttpURLConnection connection) throws Exception {
        try (java.io.InputStream input = connection.getInputStream();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
