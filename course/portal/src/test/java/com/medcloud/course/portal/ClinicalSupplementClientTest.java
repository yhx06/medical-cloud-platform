package com.medcloud.course.portal;
import com.medcloud.course.contract.Role;
import com.medcloud.course.portal.web.PaasClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClinicalSupplementClientTest {
    @Test void patientReadsOnlyComplaintAndOwnRecordWithFiveItemHistory()throws Exception{
        java.util.List<String> calls=new java.util.ArrayList<>();com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api",x->{calls.add(x.getRequestURI().toString());byte[] bytes="{\"code\":\"OK\",\"data\":{\"description\":\"自述\",\"revision\":3,\"writable\":false,\"draft\":{\"secret\":\"private\"}}}".getBytes("UTF-8");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();});server.start();
        try{Object workspace=Class.forName("com.medcloud.course.portal.web.ClinicalSupplementWorkspace").getConstructor(PaasClient.class).newInstance(new PaasClient("http://127.0.0.1:"+server.getAddress().getPort()+"/api"));java.lang.reflect.Method load=workspace.getClass().getMethod("load",javax.servlet.http.HttpServletRequest.class,String.class,Role.class,String.class);MockHttpServletRequest r=new MockHttpServletRequest();r.setServletPath("/patient/complaint");load.invoke(workspace,r,"trusted",Role.PATIENT,ClinicalSupplementPortalTest.ID);assertEquals("自述",r.getAttribute("description"));assertEquals(false,r.getAttribute("clinicalWritable"));assertNull(r.getAttribute("clinicalWorkspace"));r=new MockHttpServletRequest();r.setServletPath("/patient/record-details");load.invoke(workspace,r,"trusted",Role.PATIENT,ClinicalSupplementPortalTest.ID);assertEquals(java.util.Arrays.asList("/api/patient/appointments/"+ClinicalSupplementPortalTest.ID+"/complaint","/api/patient/appointments/"+ClinicalSupplementPortalTest.ID+"/record/details","/api/patient/appointments/"+ClinicalSupplementPortalTest.ID+"/record/corrections?page=1&pageSize=5"),calls);r.setParameter("pageSize","6");final MockHttpServletRequest invalid=r;java.lang.reflect.InvocationTargetException e=assertThrows(java.lang.reflect.InvocationTargetException.class,()->load.invoke(workspace,invalid,"trusted",Role.PATIENT,ClinicalSupplementPortalTest.ID));assertTrue(e.getCause() instanceof IllegalArgumentException);assertEquals(4,calls.size());}finally{server.stop(0);}
    }
    @Test void administratorsCannotLoadClinicalBody()throws Exception{
        Object workspace=Class.forName("com.medcloud.course.portal.web.ClinicalSupplementWorkspace").getConstructor(PaasClient.class).newInstance(new PaasClient("http://127.0.0.1:1/api"));
        for(Role role:new Role[]{Role.HOSPITAL_ADMIN,Role.PLATFORM_ADMIN}){
            java.lang.reflect.InvocationTargetException error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->workspace.getClass().getMethod("load",javax.servlet.http.HttpServletRequest.class,String.class,Role.class,String.class).invoke(workspace,new MockHttpServletRequest(),"token",role,ClinicalSupplementPortalTest.ID));
            assertTrue(error.getCause() instanceof IllegalArgumentException);
        }
    }
}
