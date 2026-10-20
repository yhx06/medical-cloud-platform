package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.order.PaymentService;
import com.medcloud.course.paas.tenant.PlatformTenantService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantViewPrivacyTest extends TenantFixture {
    @Test
    void legacyHistoryConstructorExplicitlyDeniesBusinessWrites() {
        JsonNode json = new ObjectMapper().valueToTree(new AppointmentHistoryView("id", "BOOKED", null, null,
                "tenant", "hospital", "department", "general", "doctor", "name", "slot", null, null, null));
        assertTrue(json.has("businessWritable"), "patient history needs explicit writable metadata");
        assertFalse(json.path("businessWritable").asBoolean());
        assertTrue(json.has("tenantStatus"));
        assertTrue(json.path("tenantStatus").isNull());
    }

    @Test
    void legacyOrderConstructorExplicitlyDeniesBusinessWrites() {
        JsonNode json = new ObjectMapper().valueToTree(new PaymentOrderView("id", "appointment",
                new BigDecimal("39.25"), "UNPAID", null));
        assertTrue(json.has("businessWritable"), "patient order needs explicit writable metadata");
        assertFalse(json.path("businessWritable").asBoolean());
    }

    @Test
    void hospitalListAndContextHaveStrictSafeFieldWhitelists() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode page = mapper.valueToTree(new PlatformTenantService(manager).list(PLATFORM, 1, 20));
        assertEquals(fields("items", "total", "page", "size"), fieldNames(page));
        for (JsonNode item : page.path("items")) {
            assertEquals(fields("id", "name", "status"), fieldNames(item));
        }
        JsonNode context = mapper.valueToTree(new PlatformTenantService(manager).context(ADMIN, Role.HOSPITAL_ADMIN));
        assertEquals(fields("id", "name", "status", "businessWritable"), fieldNames(context));
    }

    @Test
    void statusRequestRejectsUnknownUpdateFieldsEvenWithLenientMapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        TenantStatusRequest valid = mapper.readValue("{\"status\":\"DISABLED\",\"expectedStatus\":\"ACTIVE\"}", TenantStatusRequest.class);
        assertEquals("DISABLED", valid.getStatus());
        assertEquals("ACTIVE", valid.getExpectedStatus());
        assertEquals(fields("status", "expectedStatus"), fieldNames(mapper.valueToTree(valid)));
        for (String field : new String[] {"name", "tenantId", "accountStatus", "id"}) {
            String json = "{\"status\":\"DISABLED\",\"expectedStatus\":\"ACTIVE\",\"" + field + "\":\"unsafe\"}";
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> mapper.readValue(json, TenantStatusRequest.class));
        }
    }

    @Test
    void patientHistoryAndOrderViewsReadRealHospitalStateAndKeepLegacyFields() {
        BookingService bookings = new BookingService(manager);
        PaymentService payments = new PaymentService(manager);
        AuthService auth = new AuthService(manager, SECRET);
        String bearer = "Bearer " + AuditLocalTransactions.login(manager, auth, new LoginRequest("patient_a", PASSWORD)).getAccessToken();
        PaymentResource resource = new PaymentResource(auth, payments, AuditClinicalFixture.legacyRunner(manager));
        assertTrue(bookings.forPatient(PATIENT, 1, 100).getItems().get(0).isBusinessWritable());
        assertEquals("ACTIVE", bookings.forPatient(PATIENT, 1, 100).getItems().get(0).getTenantStatus());
        assertTrue(resource.order(bearer, "unpaid").getData().isBusinessWritable());
        setTenantStatus("DISABLED");
        assertFalse(bookings.forPatient(PATIENT, 1, 100).getItems().get(0).isBusinessWritable());
        assertEquals("DISABLED", bookings.forPatient(PATIENT, 1, 100).getItems().get(0).getTenantStatus());
        JsonNode historyJson = new ObjectMapper().valueToTree(bookings.forPatient(PATIENT, 1, 100).getItems().get(0));
        assertEquals(fields("id", "status", "bookedOn", "bookedAt", "tenantId", "hospitalName", "departmentId",
                "departmentName", "doctorId", "doctorName", "slotId", "slotDate", "startTime", "endTime",
                "tenantStatus", "businessWritable"), fieldNames(historyJson));
        PaymentOrderView unpaid = resource.order(bearer, "unpaid").getData();
        assertFalse(unpaid.isBusinessWritable());
        assertEquals("order-unpaid", unpaid.getId());
        assertEquals("unpaid", unpaid.getAppointmentId());
        assertEquals(new BigDecimal("39.25"), unpaid.getAmount());
        assertEquals("UNPAID", unpaid.getStatus());
        assertNull(unpaid.getPaidAt());
        assertEquals(fields("id", "appointmentId", "amount", "status", "paidAt", "paymentReference", "businessWritable"),
                fieldNames(new ObjectMapper().valueToTree(unpaid)));
        assertFalse(resource.order(bearer, "paid").getData().isBusinessWritable());
        manager.getTransaction().begin();
        PlatformTenantTest.assertStatus(409, () -> resource.pay(bearer, "order-unpaid"));
        PlatformTenantTest.assertStatus(409, () -> resource.pay(bearer, "order-paid"));
        manager.getTransaction().rollback();
        PlatformTenantTest.assertStatus(403, () -> payments.businessWritable(OTHER, "order-unpaid"));
        PlatformTenantTest.assertStatus(403, () -> payments.businessWritable(PATIENT, "missing"));
        PlatformTenantTest.assertStatus(403, () -> payments.businessWritable(ADMIN, "order-unpaid"));
        setTenantStatus("ACTIVE");
        manager.getTransaction().begin();
        PaymentOrderView paid = resource.pay(bearer, "order-unpaid").getData();
        manager.getTransaction().commit();
        assertTrue(paid.isBusinessWritable());
        assertEquals("PAID", paid.getStatus());
        assertNotNull(paid.getPaidAt());
        String adminBearer = "Bearer " + AuditLocalTransactions.login(manager, auth, new LoginRequest("admin_a", PASSWORD)).getAccessToken();
        for (PaymentOrderView order : new HospitalResource(auth, payments).orders(adminBearer, null, null, null, "1", "100").getData().getItems()) {
            assertFalse(order.isBusinessWritable(), "hospital views must not grant patient payment capability");
        }
    }

    private Set<String> fields(String... names) { return new HashSet<String>(Arrays.asList(names)); }
    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
