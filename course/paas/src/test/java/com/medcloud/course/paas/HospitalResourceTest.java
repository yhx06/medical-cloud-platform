package com.medcloud.course.paas;

import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.HospitalDepartmentService;
import com.medcloud.course.paas.tenant.HospitalDoctorService;
import com.medcloud.course.paas.tenant.Tenant;
import com.medcloud.course.paas.schedule.ScheduleSlot;
import com.medcloud.course.paas.appointment.Appointment;
import com.medcloud.course.paas.auth.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HospitalResourceTest {
    @Test
    void departmentsAreScopedToActiveHospitalAndForeignIdsAreDenied() {
        Fixture f = new Fixture();
        try {
            f.begin();
            Department created = f.departments.create(f.a, "心内科");
            assertEquals(f.tenantA, created.getTenantId());
            assertEquals(1, f.departments.list(f.a, 1, 100).getItems().size());
            assertEquals(1, f.departments.list(f.b, 1, 100).getItems().size()); // fixture preloads this hospital's department
            assertEquals("心血管科", f.departments.update(f.a, created.getId(), "心血管科", "ACTIVE").getName());
            assertThrows(AccessDeniedException.class,
                    () -> f.departments.update(f.b, created.getId(), "偷改", "ACTIVE"));
            assertThrows(AccessDeniedException.class, () -> f.departments.update(f.a, UUID.randomUUID().toString(), "无", "ACTIVE"));
            f.commit();
        } finally { f.close(); }
    }

    @Test
    void doctorCreationUsesGlobalUsernameAndOneTimePassword() throws Exception {
        Fixture f = new Fixture();
        try {
            f.begin();
            Department dept = f.departments.create(f.a, "内科");
            com.medcloud.course.contract.DoctorCreateResponse created = f.doctors.create(f.a, "Doctor_A", "王医生", dept.getId());
            Account account = f.manager.find(Account.class, created.getUserId());
            assertEquals(Role.DOCTOR, account.getRole());
            assertEquals(f.tenantA, account.getTenantId());
            assertTrue(BCrypt.checkpw(created.getInitialPassword(), account.getPasswordHash()));
            assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(f.doctors.list(f.a, 1, 100).getItems()).contains(created.getInitialPassword()));
            assertEquals(1, f.doctors.list(f.a, 1, 100).getItems().size());
            assertTrue(f.doctors.list(f.b, 1, 100).getItems().isEmpty());
            assertThrows(IllegalStateException.class, () -> f.doctors.create(f.b, "doctor_a", "李医生", f.foreignDepartment.getId()));
            assertThrows(IllegalArgumentException.class, () -> f.doctors.create(f.a, "bad-name", "王医生", dept.getId()));
            f.commit();
        } finally { f.close(); }
    }

    @Test
    void doctorCannotUseForeignOrDisabledDepartmentAndForeignDoctorCannotBeEdited() {
        Fixture f = new Fixture();
        try {
            f.begin();
            Department own = f.departments.create(f.a, "外科");
            assertThrows(AccessDeniedException.class, () -> f.doctors.create(f.a, "doctor1", "王医生", f.foreignDepartment.getId()));
            f.departments.update(f.a, own.getId(), own.getName(), "DISABLED");
            assertThrows(IllegalStateException.class, () -> f.doctors.create(f.a, "doctor2", "王医生", own.getId()));
            String foreignDoctor = f.doctors.create(f.b, "doctor_b", "李医生", f.foreignDepartment.getId()).getUserId();
            assertThrows(AccessDeniedException.class, () -> f.doctors.update(f.a, foreignDoctor, "甲", own.getId(), "ACTIVE"));
            Department active = f.departments.create(f.a, "儿科");
            String ownDoctor = f.doctors.create(f.a, "doctor_c", "陈医生", active.getId()).getUserId();
            assertThrows(AccessDeniedException.class,
                    () -> f.doctors.update(f.a, ownDoctor, "陈医生", f.foreignDepartment.getId(), "ACTIVE"));
            f.commit();
        } finally { f.close(); }
    }

    @Test
    void referencedDepartmentAndDoctorRemainRowsAndPendingAppointmentBlocksDisable() {
        Fixture f = new Fixture();
        try {
            f.begin();
            Department dept = f.departments.create(f.a, "骨科");
            String doctor = f.doctors.create(f.a, "bone_doctor", "骨科医师", dept.getId()).getUserId();
            String slotId = UUID.randomUUID().toString();
            f.manager.persist(new ScheduleSlot(slotId, f.tenantA, dept.getId(), doctor,
                    Date.valueOf("2030-10-01"), Time.valueOf("09:00:00"), Time.valueOf("10:00:00"), 2, BigDecimal.TEN));
            assertThrows(IllegalStateException.class, () -> f.departments.update(f.a, dept.getId(), dept.getName(), "DISABLED"));
            f.manager.persist(new Appointment(UUID.randomUUID().toString(), f.patientId, slotId, f.tenantA,
                    doctor, BigDecimal.TEN, Date.valueOf("2030-09-30")));
            assertThrows(IllegalStateException.class,
                    () -> f.doctors.update(f.a, doctor, "骨科医师", dept.getId(), "DISABLED"));
            assertThrows(IllegalStateException.class, () -> f.departments.update(f.a, dept.getId(), dept.getName(), "DISABLED"));
            assertNotNull(f.manager.find(Account.class, doctor));
            assertNotNull(f.manager.find(Department.class, dept.getId()));
            f.commit();
        } finally { f.close(); }
    }

    private static final class Fixture {
        final EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        final EntityManager manager = factory.createEntityManager();
        final String tenantA = UUID.randomUUID().toString();
        final String tenantB = UUID.randomUUID().toString();
        final String patientId = UUID.randomUUID().toString();
        final CurrentActor a;
        final CurrentActor b;
        final Department foreignDepartment;
        final HospitalDepartmentService departments = new HospitalDepartmentService(manager);
        final HospitalDoctorService doctors = new HospitalDoctorService(manager);
        Fixture() {
            manager.getTransaction().begin();
            manager.persist(new Tenant(tenantA, "甲医院"));
            manager.persist(new Tenant(tenantB, "乙医院"));
            String aId = UUID.randomUUID().toString(), bId = UUID.randomUUID().toString();
            manager.persist(new Account(aId, "admin_a", "hash", Role.HOSPITAL_ADMIN, tenantA));
            manager.persist(new Account(bId, "admin_b", "hash", Role.HOSPITAL_ADMIN, tenantB));
            manager.persist(new Account(patientId, "patient", "hash", Role.PATIENT, null));
            foreignDepartment = new Department(UUID.randomUUID().toString(), tenantB, "内科");
            manager.persist(foreignDepartment);
            manager.getTransaction().commit();
            a = new CurrentActor(aId, Role.HOSPITAL_ADMIN, tenantA);
            b = new CurrentActor(bId, Role.HOSPITAL_ADMIN, tenantB);
        }
        void begin() { manager.getTransaction().begin(); }
        void commit() { manager.getTransaction().commit(); }
        void close() { if (manager.getTransaction().isActive()) manager.getTransaction().rollback(); manager.close(); factory.close(); }
    }
}
