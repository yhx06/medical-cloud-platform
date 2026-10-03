package com.medcloud.course.portal;

import org.junit.jupiter.api.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises compiled JSPs: removing fail-closed guards makes these checks fail. */
class ReadOnlyWorkspaceJspTest {
    @BeforeAll static void start() throws Exception { WorkspaceJspTest.start(); }
    @AfterAll static void stop() throws Exception { WorkspaceJspTest.stop(); }

    @Test void disabledAndMissingHospitalKeepHistoryWithoutWritableForms() throws Exception {
        for (String state : new String[]{"DISABLED", "missing", "UNKNOWN"}) {
            String hospital = WorkspaceJspTest.render("hospital?state=" + state);
            assertTrue(hospital.contains("id=\"orders\""));
            assertTrue(hospital.contains("order-id"));
            assertTrue(hospital.contains("历史可查"));
            assertFalse(Pattern.compile("<form[^>]*method=\"post\"").matcher(hospital).find(), state);
            String doctor = WorkspaceJspTest.render("doctor?state=" + state);
            assertTrue(doctor.contains("12345678-1234-1234-1234-123456789012"));
            assertTrue(doctor.contains("演示主诉 &lt;script&gt;unsafe&lt;/script&gt;"));
            assertTrue(doctor.contains("历史可查"));
            assertFalse(Pattern.compile("<form[^>]*method=\"post\"").matcher(doctor).find(), state);
        }
    }

    @Test void patientRequiresExplicitWritableFieldsAndKeepsRecordAndBill() throws Exception {
        for (String suffix : new String[]{"state=DISABLED", "state=missing", "state=UNKNOWN", "absent=1"}) {
            String html = WorkspaceJspTest.render("patient?" + suffix);
            assertTrue(html.contains("booked-id"));
            assertTrue(html.contains("历史诊断 &lt;script&gt;unsafe&lt;/script&gt;"));
            assertTrue(html.contains("order-id") || html.contains("诊疗账单"));
            assertFalse(html.contains("action=\"/portal/patient/cancel\""), suffix);
            assertFalse(html.contains("action=\"/portal/patient/pay\""), suffix);
            assertTrue(html.contains("历史可查"));
            assertTrue(html.contains("查看病历与账单</a>"));
        }
    }

    @Test void cancellationHonorsShanghaiVisitStartCutoff() throws Exception {
        String html = WorkspaceJspTest.render("patient?expired=1");
        assertFalse(html.contains("action=\"/portal/patient/cancel\""));
        assertTrue(html.contains("取消截止"));
        assertTrue(html.contains("action=\"/portal/patient/pay\""));
    }

    @Test void actualHistoryDtoDateSerializationStillAllowsFutureCancellation() throws Exception {
        String html = WorkspaceJspTest.render("patient?serialized=1");
        assertTrue(html.contains("action=\"/portal/patient/cancel\""));
        assertTrue(html.contains("name=\"appointmentId\" value=\"booked-id\""));
        Matcher appointment = Pattern.compile("<tr><td class=\"identifier\">booked-id</td>([\\s\\S]*?)</tr>").matcher(html);
        assertTrue(appointment.find());
        assertTrue(appointment.group(1).contains("<td>2099-01-01 09:00:00–10:00:00</td>"), "Shanghai midnight must not become the prior UTC date");
        assertFalse(html.contains("4070880000000"), "Numeric SQL Date must render as the Shanghai visit date");
    }

    @Test void activeRowsStillRequireValidVisitTimeAndUnpaidBillState() throws Exception {
        String html = WorkspaceJspTest.render("patient?badDate=1&orderState=PAID");
        assertFalse(html.contains("action=\"/portal/patient/cancel\""));
        assertFalse(html.contains("action=\"/portal/patient/pay\""));
        assertTrue(html.contains("已缴费"));
        assertTrue(html.contains("查看病历与账单</a>"));
    }
}
