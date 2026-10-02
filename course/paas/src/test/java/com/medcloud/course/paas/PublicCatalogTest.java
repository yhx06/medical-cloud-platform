package com.medcloud.course.paas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.contract.Role;
import com.medcloud.course.paas.api.CatalogResource;
import com.medcloud.course.paas.auth.Account;
import com.medcloud.course.paas.schedule.DirectoryService;
import com.medcloud.course.paas.tenant.Department;
import com.medcloud.course.paas.tenant.DoctorProfile;
import com.medcloud.course.paas.tenant.Tenant;
import org.jboss.resteasy.core.ResourceMethodInvoker;
import org.jboss.resteasy.core.ResourceMethodRegistry;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.junit.jupiter.api.Test;
import javax.persistence.*;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

class PublicCatalogTest {
    @Test void publicDirectoryOnlyExposesActiveRelatedNamesWithoutAccountSecrets() throws Exception {
        EntityManagerFactory factory = Persistence.createEntityManagerFactory("medicalCloudCourseTest");
        EntityManager manager = factory.createEntityManager();
        try {
            manager.getTransaction().begin();
            manager.persist(new Tenant("a", "甲医院"));
            manager.persist(new Tenant("b", "乙医院"));
            manager.persist(new Tenant("off", "停用医院"));
            manager.persist(new Department("da", "a", "内科"));
            manager.persist(new Department("db", "b", "儿科"));
            Department disabled = new Department("disabled", "a", "停用科室");
            disabled.update("停用科室", "DISABLED"); manager.persist(disabled);
            manager.persist(new Department("doff", "off", "隐藏科室"));
            doctor(manager, "ok", "a", "da", Role.DOCTOR, "ACTIVE");
            doctor(manager, "other", "b", "db", Role.DOCTOR, "ACTIVE");
            doctor(manager, "inactive", "a", "da", Role.DOCTOR, "DISABLED");
            doctor(manager, "wrong-role", "a", "da", Role.PATIENT, "ACTIVE");
            doctor(manager, "wrong-department", "a", "db", Role.DOCTOR, "ACTIVE");
            doctor(manager, "off-department", "a", "disabled", Role.DOCTOR, "ACTIVE");
            doctor(manager, "off-tenant", "off", "doff", Role.DOCTOR, "ACTIVE");
            manager.createQuery("update Tenant t set t.status = 'DISABLED' where t.id = 'off'").executeUpdate();
            manager.getTransaction().commit(); manager.clear();
            CatalogResource resource = new CatalogResource(new DirectoryService(manager));
            JsonNode hospitals = invoke(resource, "/catalog/hospitals");
            assertEquals(2, hospitals.size());
            JsonNode hospitalA = null;
            for (JsonNode hospital : hospitals) if ("a".equals(hospital.path("id").asText())) hospitalA = hospital;
            assertNotNull(hospitalA);
            assertEquals("甲医院", hospitalA.path("name").asText());
            assertEquals(2, hospitalA.size(), "Only id/name belong in the public DTO");
            JsonNode departments = invoke(resource, "/catalog/departments?tenantId=a", "a");
            assertEquals(1, departments.size()); assertEquals("da", departments.get(0).path("id").asText());
            assertTrue(invoke(resource, "/catalog/departments?tenantId=off", "off").isEmpty());
            JsonNode doctors = invoke(resource, "/catalog/doctors?tenantId=a&departmentId=da", "a", "da");
            assertEquals(1, doctors.size());
            assertEquals("ok", doctors.get(0).path("id").asText());
            assertEquals("医生ok", doctors.get(0).path("name").asText());
            assertEquals(2, doctors.get(0).size(), "Do not expose login, hashes or clinical data");
            assertTrue(invoke(resource, "/catalog/doctors?tenantId=a&departmentId=db", "a", "db").isEmpty());
            assertEquals(1, invoke(resource, "/catalog/doctors?tenantId=a", "a", null).size());
            assertTrue(invoke(resource, "/catalog/doctors?tenantId=off", "off", null).isEmpty());
        } finally { manager.close(); factory.close(); }
    }

    @Test void lowerDirectoriesRequireHospitalRatherThanListingEveryTenant() throws Exception {
        CatalogResource resource = new CatalogResource(new DirectoryService());
        assertThrows(IllegalArgumentException.class, () -> invoke(resource, "/catalog/departments", (Object) null));
        assertThrows(IllegalArgumentException.class, () -> invoke(resource, "/catalog/doctors", null, null));
    }

    private static JsonNode invoke(CatalogResource resource, String path, Object... args) throws Exception {
        ResourceMethodRegistry registry = new ResourceMethodRegistry(new ResteasyProviderFactory());
        registry.addPerRequestResource(CatalogResource.class);
        ResourceMethodInvoker route = assertDoesNotThrow(() -> (ResourceMethodInvoker)
                registry.getResourceInvoker(MockHttpRequest.get(path)), "Missing public catalog route: " + path);
        try {
            return new ObjectMapper().valueToTree(route.getMethod().invoke(resource, args)).path("data");
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }

    private static void doctor(EntityManager manager, String id, String tenant, String department,
                               Role role, String status) {
        Account account = new Account(id, "login_" + id, "private-hash", role, tenant);
        account.setStatus(status); manager.persist(account);
        manager.persist(new DoctorProfile(id, tenant, department, "医生" + id));
    }
}
