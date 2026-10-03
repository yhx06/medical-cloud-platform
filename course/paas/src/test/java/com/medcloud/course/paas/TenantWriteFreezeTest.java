package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.appointment.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.consultation.*;
import com.medcloud.course.paas.order.*;
import com.medcloud.course.paas.schedule.*;
import com.medcloud.course.paas.tenant.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TenantWriteFreezeTest extends TenantFixture {
    @ParameterizedTest(name = "disabled hospital rejects {0} without changing persisted data")
    @ValueSource(strings = {"book", "cancel", "start", "complete", "pay", "repeatPay", "createDepartment",
            "updateDepartment", "createDoctor", "updateDoctor", "createSlot", "updateSlot"})
    void everyWriteRejectsFreshDatabaseDisabledStatusAndPreservesAllBusinessFields(String operation) {
        assertEquals("ACTIVE", manager.find(Tenant.class, "tenant-a").getStatus());
        manager.find(Account.class, "doctor-a");
        setTenantStatus("DISABLED");
        List<String> before = snapshot();
        manager.getTransaction().begin();
        try {
            assertThrows(IllegalStateException.class, () -> write(operation));
        } finally {
            manager.getTransaction().rollback();
            manager.clear();
        }
        assertEquals(before, snapshot(), "rejected operation must leave rows, statuses, timestamps and amounts unchanged");
        assertEquals("BOOKED", manager.find(Appointment.class, "booked").getStatus());
        assertEquals("IN_PROGRESS", manager.find(Appointment.class, "progress").getStatus());
        assertEquals("UNPAID", manager.find(PaymentOrder.class, "order-unpaid").getStatus());
        assertEquals("PAID", manager.find(PaymentOrder.class, "order-paid").getStatus());
    }

    private void write(String operation) {
        switch (operation) {
            case "book": new BookingService(manager).book(OTHER, "slot-a", TODAY); break;
            case "cancel": new BookingService(manager).cancel(PATIENT, "booked", BEFORE); break;
            case "start": new ClinicalService(manager).start(DOCTOR, "booked", BEFORE); break;
            case "complete": new ClinicalService(manager).complete(DOCTOR, "progress", "虚构诊断", "演示建议"); break;
            case "pay": new PaymentService(manager).pay(PATIENT, "order-unpaid"); break;
            case "repeatPay": new PaymentService(manager).pay(PATIENT, "order-paid"); break;
            case "createDepartment": new HospitalDepartmentService(manager).create(ADMIN, "新科室"); break;
            case "updateDepartment": new HospitalDepartmentService(manager).update(ADMIN, "department-a", "修改科室", "ACTIVE"); break;
            case "createDoctor": new HospitalDoctorService(manager).create(ADMIN, "new_doctor", "新医生", "department-a"); break;
            case "updateDoctor": new HospitalDoctorService(manager).update(ADMIN, "doctor-a", "修改医生", "department-a", "ACTIVE"); break;
            case "createSlot":
                com.medcloud.course.contract.SlotRequest fresh = request();
                fresh.setStartTime(java.sql.Time.valueOf("11:00:00"));
                fresh.setEndTime(java.sql.Time.valueOf("12:00:00"));
                new SlotAdminService(manager).create(ADMIN, fresh); break;
            case "updateSlot": new SlotAdminService(manager).update(ADMIN, "slot-a", request()); break;
            default: throw new AssertionError(operation);
        }
    }

    @Test
    void disabledHospitalStillRejectsForeignObjectsBeforeBusinessFreeze() {
        setTenantStatus("DISABLED");
        manager.getTransaction().begin();
        assertThrows(AccessDeniedException.class, () -> new BookingService(manager).cancel(OTHER, "booked", BEFORE));
        assertThrows(AccessDeniedException.class, () -> new ClinicalService(manager).start(
                new CurrentActor("doctor-b", Role.DOCTOR, "tenant-a"), "booked", BEFORE));
        assertThrows(AccessDeniedException.class, () -> new PaymentService(manager).pay(OTHER, "order-paid"));
        assertThrows(AccessDeniedException.class, () -> new HospitalDepartmentService(manager).update(ADMIN, "department-b", "修改", "ACTIVE"));
        assertThrows(AccessDeniedException.class, () -> new HospitalDoctorService(manager).update(ADMIN, "doctor-b", "修改", "department-a", "ACTIVE"));
        assertThrows(AccessDeniedException.class, () -> new SlotAdminService(manager).update(ADMIN, "slot-b", request()));
        manager.getTransaction().rollback();
    }

    @Test
    void reenablePreservesCancellationDeadlineAndIndependentDoctorDisable() {
        setTenantStatus("DISABLED");
        externalUpdate("update Account a set a.status = 'DISABLED' where a.id = 'doctor-a'");
        setTenantStatus("ACTIVE");
        manager.getTransaction().begin();
        assertThrows(IllegalStateException.class, () -> new BookingService(manager).cancel(PATIENT, "booked", Instant.parse("2030-05-21T01:00:00Z")));
        assertThrows(SecurityException.class, () -> new ClinicalService(manager).start(DOCTOR, "booked", BEFORE));
        manager.getTransaction().rollback();
        manager.clear();
        assertEquals("BOOKED", manager.find(Appointment.class, "booked").getStatus());
        assertNull(manager.find(Appointment.class, "booked").getStartedAt());
        assertEquals("DISABLED", manager.find(Account.class, "doctor-a").getStatus());
    }
}
