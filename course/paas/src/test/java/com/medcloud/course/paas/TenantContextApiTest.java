package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.*;
import com.medcloud.course.paas.auth.*;
import com.medcloud.course.paas.tenant.*;
import org.junit.jupiter.api.Test;
import javax.ws.rs.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantContextApiTest extends TenantFixture {
    @Test
    void contextUsesAccountHospitalAndTracksDisableAndReenable() {
        PlatformTenantService service = new PlatformTenantService(manager);
        TenantContextView active = service.context(ADMIN, Role.HOSPITAL_ADMIN);
        assertEquals("tenant-a", active.getId(), "actor's stale tenant-b claim must not set scope");
        assertEquals("虚构医院a", active.getName());
        assertTrue(active.isBusinessWritable());
        setTenantStatus("DISABLED");
        assertEquals("DISABLED", service.context(ADMIN, Role.HOSPITAL_ADMIN).getStatus());
        assertFalse(service.context(DOCTOR, Role.DOCTOR).isBusinessWritable());
        assertEquals("tenant-b", service.context(new CurrentActor("doctor-b", Role.DOCTOR, "tenant-a"), Role.DOCTOR).getId());
        setTenantStatus("ACTIVE");
        assertTrue(service.context(ADMIN, Role.HOSPITAL_ADMIN).isBusinessWritable());
    }

    @Test
    void contextRoutesRequireTheirOwnRoleAndRetainAccessWhileHospitalDisabled() throws Exception {
        AuthService auth = new AuthService(manager, SECRET);
        String admin = bearer(auth, "admin_a");
        String doctor = bearer(auth, "doctor_a");
        TenantContextResource resource = new TenantContextResource(auth, new PlatformTenantService(manager));
        HospitalResource hospital = new HospitalResource(auth, new PlatformTenantService(manager), null);
        setTenantStatus("DISABLED");
        assertEquals("tenant-a", hospital.context(admin).getData().getId());
        assertFalse(resource.doctor(doctor).getData().isBusinessWritable());
        PlatformTenantTest.assertStatus(403, () -> hospital.context(doctor));
        PlatformTenantTest.assertStatus(403, () -> resource.doctor(admin));
        PlatformTenantTest.assertStatus(401, () -> hospital.context(null));
        externalUpdate("update Account a set a.status = 'DISABLED' where a.id = 'doctor-a'");
        PlatformTenantTest.assertStatus(401, () -> resource.doctor(doctor));
        assertEquals("doctor/context", TenantContextResource.class.getAnnotation(Path.class).value());
        assertEquals("context", HospitalResource.class.getMethod("context", String.class).getAnnotation(Path.class).value());
        assertNotNull(TenantContextResource.class.getMethod("doctor", String.class).getAnnotation(GET.class));
    }

    @Test
    void platformRoutesUseAuthenticationAndExposeStablePaginationDefaults() throws Exception {
        AuthService auth = new AuthService(manager, SECRET);
        PlatformTenantResource resource = new PlatformTenantResource(auth, new PlatformTenantService(manager));
        String platform = bearer(auth, "platform");
        assertEquals(2, resource.list(platform, 1, 20).getData().getItems().size());
        PlatformTenantTest.assertStatus(403, () -> resource.list(bearer(auth, "admin_a"), 1, 20));
        manager.getTransaction().begin();
        assertEquals("DISABLED", resource.changeStatus(platform, "tenant-a", new TenantStatusRequest("DISABLED", "ACTIVE")).getData().getStatus());
        manager.getTransaction().commit();
        assertEquals("platform/tenants", PlatformTenantResource.class.getAnnotation(Path.class).value());
        java.lang.reflect.Method list = PlatformTenantResource.class.getMethod("list", String.class, int.class, int.class);
        assertNotNull(list.getAnnotation(GET.class));
        java.lang.annotation.Annotation[][] parameters = list.getParameterAnnotations();
        assertEquals("1", defaultValue(parameters[1]));
        assertEquals("20", defaultValue(parameters[2]));
        java.lang.reflect.Method update = PlatformTenantResource.class.getMethod("changeStatus", String.class, String.class, TenantStatusRequest.class);
        assertNotNull(update.getAnnotation(PUT.class));
        assertEquals("{id}/status", update.getAnnotation(Path.class).value());
    }

    @Test
    void contextDoesNotExposeMissingOrUnknownHospital() {
        PlatformTenantService service = new PlatformTenantService(manager);
        setTenantStatus("UNKNOWN");
        PlatformTenantTest.assertStatus(401, () -> service.context(ADMIN, Role.HOSPITAL_ADMIN));
        externalUpdate("delete from Tenant t where t.id = 'tenant-a'");
        PlatformTenantTest.assertStatus(401, () -> service.context(DOCTOR, Role.DOCTOR));
    }

    private String bearer(AuthService auth, String username) {
        return "Bearer " + auth.login(new LoginRequest(username, PASSWORD)).getAccessToken();
    }

    private String defaultValue(java.lang.annotation.Annotation[] annotations) {
        for (java.lang.annotation.Annotation annotation : annotations) {
            if (annotation instanceof DefaultValue) return ((DefaultValue) annotation).value();
        }
        return null;
    }
}
