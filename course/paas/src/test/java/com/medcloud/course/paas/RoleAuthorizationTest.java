package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.appointment.BookingService;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.order.PaymentService;
import org.junit.jupiter.api.Test;

import java.sql.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoleAuthorizationTest {
    private final BookingService bookings = new BookingService(null);
    private final PaymentService payments = new PaymentService(null);
    private final CurrentActor patient = new CurrentActor("patient", Role.PATIENT, null);
    private final CurrentActor hospital = new CurrentActor("admin", Role.HOSPITAL_ADMIN, "tenant");

    @Test
    void authenticatedPatientCannotReadHospitalOrders() {
        assertFailureStatus(403, () -> payments.forHospital(patient, null, null, null));
    }

    @Test
    void hospitalCannotPayPatientOrder() {
        assertFailureStatus(403, () -> payments.pay(hospital, "order"));
    }

    @Test
    void hospitalCannotMakePatientBooking() {
        assertFailureStatus(403, () -> bookings.book(hospital, "slot", Date.valueOf("2030-05-21")));
    }

    @Test
    void patientCannotReadDoctorAppointments() {
        assertFailureStatus(403, () -> bookings.forDoctor(patient));
    }

    @Test
    void missingAuthenticationRemains401() {
        assertFailureStatus(401, () -> payments.forHospital(null, null, null, null));
        assertFailureStatus(401, () -> payments.pay(null, "order"));
        assertFailureStatus(401, () -> bookings.book(null, "slot", Date.valueOf("2030-05-21")));
        assertFailureStatus(401, () -> bookings.forDoctor(null));
    }

    private static void assertFailureStatus(int expected, Runnable operation) {
        RuntimeException failure = assertThrows(RuntimeException.class, operation::run);
        assertEquals(expected, new ApiFailureMapper().toResponse(failure).getStatus());
    }
}
