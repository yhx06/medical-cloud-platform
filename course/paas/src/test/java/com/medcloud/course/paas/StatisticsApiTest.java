package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.StatisticsResource;
import com.medcloud.course.paas.auth.*;
import org.junit.jupiter.api.Test;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsApiTest extends StatisticsFixture {
    @Test void routesAuthenticateRejectWrongRoleAndSupportDisabledHospitals() throws Exception {
        AuthService auth = new AuthService(manager, SECRET);
        StatisticsResource resource = new StatisticsResource(auth, service);
        String admin = bearer(auth, "admin_a");
        String platform = bearer(auth, "platform");
        assertEquals(4, resource.hospital(admin,"2026-10-01","2026-10-02",uri()).getData().getMetrics().getAppointmentTotal());
        assertEquals(5, resource.platform(platform,"2026-10-01","2026-10-02").getData().getMetrics().getAppointmentTotal());
        PlatformTenantTest.assertStatus(401, () -> resource.hospital(null,null,null,uri()));
        PlatformTenantTest.assertStatus(401, () -> resource.platform(null,null,null));
        PlatformTenantTest.assertStatus(403, () -> resource.platform(admin,null,null));
        PlatformTenantTest.assertStatus(403, () -> resource.hospital(platform,null,null,uri()));
        PlatformTenantTest.assertStatus(403, () -> resource.hospital(bearer(auth,"doctor_a"),null,null,uri()));
        PlatformTenantTest.assertStatus(403, () -> service.platform(new CurrentActor("admin-a",Role.PLATFORM_ADMIN,null),null,null));
        execute("update tenant set status = 'DISABLED' where id = 'tenant-a'");
        assertEquals("DISABLED", resource.hospital(admin,null,null,uri()).getData().getHospitals().get(0).getTenant().getStatus());
        execute("update user_account set status = 'DISABLED' where id = 'admin-a'");
        PlatformTenantTest.assertStatus(401, () -> resource.hospital(admin,null,null,uri()));
        PlatformTenantTest.assertStatus(401, () -> service.hospital(ADMIN,null,null));
        PlatformTenantTest.assertStatus(403, () -> service.hospital(PLATFORM,null,null));
        assertEquals("/", StatisticsResource.class.getAnnotation(Path.class).value());
        assertNotNull(StatisticsResource.class.getMethod("hospital",String.class,String.class,String.class,UriInfo.class).getAnnotation(GET.class));
        assertEquals("hospital/statistics", StatisticsResource.class.getMethod("hospital",String.class,String.class,String.class,UriInfo.class).getAnnotation(Path.class).value());
        assertEquals("platform/statistics", StatisticsResource.class.getMethod("platform",String.class,String.class,String.class).getAnnotation(Path.class).value());
        assertNotNull(StatisticsResource.class.getMethod("platform",String.class,String.class,String.class).getAnnotation(GET.class));
    }

    @Test void hospitalRejectsAnyTenantParameterIncludingEmptyAndRepeatedBeforeQuerying() {
        AuthService auth = new AuthService(manager, SECRET);
        StatisticsResource resource = new StatisticsResource(auth, service);
        String admin = bearer(auth, "admin_a");
        for (String[] values : new String[][] {{"tenant-b"},{""},{"tenant-a","tenant-b"}})
            PlatformTenantTest.assertStatus(400, () -> resource.hospital(admin,"2026-10-01","2026-10-02",uri(values)));
        PlatformTenantTest.assertStatus(400, () -> resource.hospital(admin,"2026-10-01",null,uri()));
        PlatformTenantTest.assertStatus(400, () -> resource.platform(bearer(auth,"platform"),"2026-02-29","2026-03-01"));
    }

    private String bearer(AuthService auth,String username) { return "Bearer " + auth.login(new LoginRequest(username,PASSWORD)).getAccessToken(); }

    private UriInfo uri(String... tenantValues) {
        MultivaluedMap<String,String> params = new MultivaluedHashMap<String,String>();
        for (String value : tenantValues) params.add("tenantId",value);
        return (UriInfo) Proxy.newProxyInstance(UriInfo.class.getClassLoader(),new Class<?>[]{UriInfo.class},
            (proxy,method,args) -> { if ("getQueryParameters".equals(method.getName())) return params; throw new UnsupportedOperationException(method.getName()); });
    }
}
