package com.medcloud.course.portal;

import com.medcloud.course.portal.web.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class PaasSafeErrorTest {
    // A raw backend message must never become public, even for a recognized business code.
    @Test void onlyMatchingStableCodesBecomeActionableSafeErrors() throws Exception {
        String[] codes={"HOSPITAL_ONBOARDING_INVALID","USERNAME_EXISTS","OPERATION_KEY_CONFLICT","ADMIN_STATUS_CONFLICT","LAST_ACTIVE_HOSPITAL_ADMIN","APPOINTMENT_CONFLICT"};
        String[] messages={"开通信息无效，请检查医院名称、用户名、密码和操作编号","用户名已被使用，请核对账号后再提交","操作编号与原提交内容冲突，请复核原信息，不要直接换编号重发","管理员状态已变化，请另开列表复核，不要直接覆盖当前状态","不能停用最后一位启用的医院管理员，请先增补或启用另一位管理员后复核","您在该时段已有其他预约，请选择不相重叠的号源后再试"};
        for(int i=0;i<codes.length;i++){String code=codes[i];int status=code.equals(codes[0])?400:409;
            PaasRejectedException error=reject(status,"{\"code\":\""+code+"\",\"message\":\"submitted-secret SQL\"}");
            assertEquals(code,code(error));assertEquals(messages[i],error.getMessage());assertFalse(error.getMessage().contains("submitted-secret"));assertFalse(error.getMessage().contains("SQL"));
            assertNull(code(reject(status==400?409:400,"{\"code\":\""+code+"\"}")));
        }
    }
    @Test void htmlUnknownOversizeAndAuthorizationKeepGenericSafeMessages() throws Exception {
        for(String body:new String[]{"<html>submitted-secret</html>","{\"code\":\"UNKNOWN\",\"message\":\"submitted-secret\"}","{\"code\":\"USERNAME_EXISTS\",\"message\":\""+repeat('x',1048576)+"\"}"}){
            PaasRejectedException error=reject(409,body);assertNull(code(error));assertFalse(error.getMessage().contains("submitted-secret"));
        }
        for(int status:new int[]{401,403}){PaasRejectedException e=reject(status,"{\"code\":\"USERNAME_EXISTS\"}");assertEquals(status,e.getStatus());assertNull(code(e));}
    }
    @Test void trailingGarbageCannotPromoteFirstObjectToBusinessError() throws Exception {
        PaasRejectedException error=reject(409,"{\"code\":\"USERNAME_EXISTS\"} garbage");
        assertEquals(409,error.getStatus());assertNull(code(error));
        assertEquals("操作与当前状态冲突，请刷新后重试",error.getMessage());
    }
    @Test void secondJsonDocumentCannotPromoteFirstObjectToBusinessError() throws Exception {
        PaasRejectedException error=reject(409,"{\"code\":\"USERNAME_EXISTS\"}{\"code\":\"UNKNOWN\"}");
        assertEquals(409,error.getStatus());assertNull(code(error));
        assertEquals("操作与当前状态冲突，请刷新后重试",error.getMessage());
    }
    static String code(PaasRejectedException error){return assertDoesNotThrow(()->(String)error.getClass().getMethod("getCode").invoke(error));}
    static String repeat(char c,int n){char[] chars=new char[n];java.util.Arrays.fill(chars,c);return new String(chars);}
    static PaasRejectedException reject(int status,String body)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api",x->{byte[] b=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,b.length);try{x.getResponseBody().write(b);}finally{x.close();}});server.start();
        try{return assertThrows(PaasRejectedException.class,()->new PaasClient("http://127.0.0.1:"+server.getAddress().getPort()).get("/api","fake"));}finally{server.stop(0);}
    }
}
