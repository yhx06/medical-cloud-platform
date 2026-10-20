package com.medcloud.course.paas;

import com.medcloud.course.contract.AuditResult;
import com.medcloud.course.contract.DoctorProfileView;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.auth.AccessDeniedException;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.auth.CurrentActor;
import com.medcloud.course.paas.audit.AuditEvent;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.HospitalProfileService;
import com.medcloud.course.paas.tenant.Tenant;
import org.junit.jupiter.api.Test;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HospitalProfileServiceTest {
    @Test
    void hospitalAdminRenamesOwnHospitalWithValidation() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "旧医院"));
            manager.persist(new Account("admin-a", "admin_a", "hash", Role.HOSPITAL_ADMIN, "tenant-a"));
            manager.persist(new Account("admin-b", "admin_b", "hash", Role.HOSPITAL_ADMIN, "tenant-b"));
            manager.persist(new Tenant("tenant-b", "别家医院"));
            manager.getTransaction().commit();
            HospitalProfileService profiles = new HospitalProfileService(manager);

            manager.getTransaction().begin();
            assertEquals("新医院", profiles.renameHospital(new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a"), "  新医院  "));
            manager.getTransaction().commit();
            assertEquals("新医院", manager.find(Tenant.class, "tenant-a").getName());

            manager.getTransaction().begin();
            assertThrows(IllegalArgumentException.class,
                    () -> profiles.renameHospital(new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a"), ""));
            assertThrows(IllegalArgumentException.class,
                    () -> profiles.renameHospital(new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a"),
                            repeat('x', 101)));
            assertThrows(IllegalArgumentException.class,
                    () -> profiles.renameHospital(new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a"), "带\u0000控制"));
            assertThrows(AccessDeniedException.class,
                    () -> profiles.renameHospital(new CurrentActor("admin-b", Role.DOCTOR, "tenant-b"), "越权改名"));
            manager.getTransaction().rollback();
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
            factory.close();
        }
    }

    @Test
    void doctorEditsOwnTitleAndBioOnly() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "医院"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new Account("doctor-b", "doctor_b", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "王医生"));
            manager.persist(new DoctorProfile("doctor-b", "tenant-a", "department-a", "李医生"));
            manager.getTransaction().commit();
            HospitalProfileService profiles = new HospitalProfileService(manager);

            manager.getTransaction().begin();
            DoctorProfileView updated = profiles.updateDoctorProfile(
                    new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"), " 主任医师 ", "  擅长全科  ");
            manager.getTransaction().commit();
            assertEquals("主任医师", updated.getTitle());
            assertEquals("擅长全科", updated.getBio());
            DoctorProfile persisted = manager.find(DoctorProfile.class, "doctor-a");
            assertEquals("主任医师", persisted.getTitle());
            assertEquals("擅长全科", persisted.getBio());
            assertNull(manager.find(DoctorProfile.class, "doctor-b").getTitle());

            manager.getTransaction().begin();
            assertThrows(AccessDeniedException.class,
                    () -> profiles.updateDoctorProfile(new CurrentActor("doctor-a", Role.PATIENT, null), "x", "y"));
            assertThrows(IllegalArgumentException.class,
                    () -> profiles.updateDoctorProfile(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"),
                            repeat('x', 101), null));
            assertThrows(IllegalArgumentException.class,
                    () -> profiles.updateDoctorProfile(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"),
                            null, repeat('x', 1001)));
            manager.getTransaction().rollback();

            // Blank input clears the field.
            manager.getTransaction().begin();
            profiles.updateDoctorProfile(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"), "", "");
            manager.getTransaction().commit();
            assertNull(manager.find(DoctorProfile.class, "doctor-a").getTitle());
            assertNull(manager.find(DoctorProfile.class, "doctor-a").getBio());
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
            factory.close();
        }
    }

    @Test
    void doctorReadsOwnProfileAndCannotReadAnotherDoctors() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "医院"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "王医生"));
            manager.getTransaction().commit();
            HospitalProfileService profiles = new HospitalProfileService(manager);

            assertEquals("王医生", profiles.doctorProfile(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a")).getDisplayName());
            assertThrows(AccessDeniedException.class,
                    () -> profiles.doctorProfile(new CurrentActor("doctor-a", Role.PATIENT, null)));
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
            factory.close();
        }
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void renameAndProfileUpdateAreAudited() {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("tenant-a", "医院"));
            manager.persist(new Account("admin-a", "admin_a", "hash", Role.HOSPITAL_ADMIN, "tenant-a"));
            manager.persist(new Account("doctor-a", "doctor_a", "hash", Role.DOCTOR, "tenant-a"));
            manager.persist(new DoctorProfile("doctor-a", "tenant-a", "department-a", "王医生"));
            manager.getTransaction().commit();
            HospitalProfileService profiles = new HospitalProfileService(manager);

            manager.getTransaction().begin();
            profiles.renameHospital(new CurrentActor("admin-a", Role.HOSPITAL_ADMIN, "tenant-a"), "新医院");
            profiles.updateDoctorProfile(new CurrentActor("doctor-a", Role.DOCTOR, "tenant-a"), "主任医师", "简介");
            manager.getTransaction().commit();
            assertEquals(2L, manager.createQuery(
                    "select count(e) from AuditEvent e where e.result = :result", Long.class)
                    .setParameter("result", AuditResult.SUCCESS).getSingleResult());
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
            factory.close();
        }
    }
}
