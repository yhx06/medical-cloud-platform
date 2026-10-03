package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.*;
import com.medcloud.course.contract.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsPrivacyTest extends StatisticsFixture {
    static final String METRICS = "appointmentTotal appointmentBooked appointmentInProgress appointmentCanceled appointmentCompleted consultationStarted consultationCompleted orderGenerated paidOrderCount orderAmount paidRevenue";
    static final String LEGACY = "startedAppointments completedRecords generatedOrders paidOrders orderAmount paidRevenue";

    @Test void platformAndHospitalSerializedReportsOnlyExposeApprovedSafeFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (StatisticsReport report : Arrays.asList(service.platform(PLATFORM,"2026-10-01","2026-10-02"),service.hospital(ADMIN,"2026-10-01","2026-10-02"))) {
            JsonNode root = mapper.valueToTree(report);
            fields(root,"scope from to timezone generatedAt metrics daily hospitals legacyUnknownTime tenantCounts");
            fields(root.get("metrics"),METRICS);
            fields(root.get("legacyUnknownTime"),LEGACY);
            for (JsonNode day : root.get("daily")) { fields(day,"date metrics"); fields(day.get("metrics"),METRICS); }
            for (JsonNode hospital : root.get("hospitals")) {
                fields(hospital,"tenant metrics legacyUnknownTime");
                fields(hospital.get("tenant"),"id name status");
                fields(hospital.get("metrics"),METRICS);
                fields(hospital.get("legacyUnknownTime"),LEGACY);
            }
            if (!root.get("tenantCounts").isNull()) fields(root.get("tenantCounts"),"total active disabled");
            String json = mapper.writeValueAsString(report);
            for (String secret : Arrays.asList("patient-private","doctor-private","done-record","done-order","private diagnosis","private advice","legacy-order",PASSWORD,SECRET))
                assertFalse(json.contains(secret),secret);
        }
    }

    private void fields(JsonNode node,String whitelist) {
        Set<String> actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(new HashSet<String>(Arrays.asList(whitelist.split(" "))),actual);
    }
}
