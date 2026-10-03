package com.medcloud.course.paas;

import com.medcloud.course.paas.api.HospitalResource;
import com.medcloud.course.paas.api.HospitalSlotResource;
import com.medcloud.course.paas.api.PaymentResource;
import com.medcloud.course.paas.api.TenantContextResource;
import com.medcloud.course.paas.api.StatisticsResource;
import com.medcloud.course.paas.api.PlatformTenantResource;
import org.jboss.resteasy.core.ResourceMethodInvoker;
import org.jboss.resteasy.core.ResourceMethodRegistry;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class HospitalRoutingTest {
    @ParameterizedTest
    @ValueSource(strings={"/hospital/context","/hospital/statistics?from=2026-10-01&to=2026-10-02","/doctor/context","/platform/statistics"})
    void lifecycleAndStatisticsGetRoutesResolveInTheRealResteasyRegistry(String path) throws Exception {
        ResourceMethodRegistry routes = new ResourceMethodRegistry(new ResteasyProviderFactory());
        for (Class<?> type : new Class<?>[]{HospitalResource.class,HospitalSlotResource.class,PaymentResource.class,
                TenantContextResource.class,StatisticsResource.class,PlatformTenantResource.class}) routes.addPerRequestResource(type);
        ResourceMethodInvoker endpoint = assertDoesNotThrow(() -> (ResourceMethodInvoker)routes.getResourceInvoker(MockHttpRequest.get(path)),path);
        assertEquals(path.contains("statistics") ? (path.startsWith("/hospital") ? "statistics" : "platform") : (path.startsWith("/hospital") ? "context" : "doctor"),endpoint.getMethod().getName());
    }
    @Test
    void hospitalOrderRouteRemainsReachableBesideMoreSpecificHospitalResources() throws Exception {
        ResourceMethodRegistry routes = new ResourceMethodRegistry(new ResteasyProviderFactory());
        routes.addPerRequestResource(HospitalResource.class);
        routes.addPerRequestResource(HospitalSlotResource.class);
        routes.addPerRequestResource(PaymentResource.class);
        ResourceMethodInvoker orders = (ResourceMethodInvoker) routes.getResourceInvoker(
                MockHttpRequest.get("/hospital/orders?status=PAID"));
        assertEquals("orders", orders.getMethod().getName());
        ResourceMethodInvoker patient = (ResourceMethodInvoker) routes.getResourceInvoker(
                MockHttpRequest.get("/patient/appointments/example/order"));
        assertEquals(PaymentResource.class, patient.getResourceClass());
    }
}
