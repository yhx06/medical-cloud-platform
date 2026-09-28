package com.medcloud.course.paas;

import com.medcloud.course.paas.api.HospitalResource;
import com.medcloud.course.paas.api.HospitalSlotResource;
import com.medcloud.course.paas.api.PaymentResource;
import org.jboss.resteasy.core.ResourceMethodInvoker;
import org.jboss.resteasy.core.ResourceMethodRegistry;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HospitalRoutingTest {
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
