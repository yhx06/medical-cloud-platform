package com.medcloud.course.portal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.medcloud.course.portal.web.PaasClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class AttachmentClientTest {
 @TempDir Path temp;
 @Test void binaryClientSendsExactBytesAndTrustedToken() throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  AtomicReference<byte[]> bytes=new AtomicReference<>(); AtomicReference<String> mime=new AtomicReference<>(); AtomicReference<String> token=new AtomicReference<>();
  server.createContext("/api/files/uploads/x/content",e->{bytes.set(read(e.getRequestBody()));mime.set(e.getRequestHeaders().getFirst("Content-Type"));token.set(e.getRequestHeaders().getFirst("Authorization"));byte[] response="{\"code\":\"OK\",\"data\":{\"state\":\"AVAILABLE\"}}".getBytes("UTF-8");e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close();});server.start();
  try {Path file=temp.resolve("binary");Files.write(file,new byte[]{0,1,2,(byte)255});PaasClient client=new PaasClient("http://127.0.0.1:"+server.getAddress().getPort()+"/api");
   java.lang.reflect.Method method=assertDoesNotThrow(()->PaasClient.class.getMethod("putBinary",String.class,String.class,Path.class,long.class,int.class));
   method.invoke(client,"/files/uploads/x/content","session-token",file,4L,2);
   assertArrayEquals(new byte[]{0,1,2,(byte)255},bytes.get());assertEquals("application/octet-stream",mime.get());assertEquals("Bearer session-token",token.get());
  }finally{server.stop(0);}
 }
 @Test void oversizedFileNeverReachesBackend()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();server.createContext("/",e->{calls.incrementAndGet();e.close();});server.start();
  try{Path file=temp.resolve("oversize");Files.write(file,new byte[]{1,2,3});PaasClient client=new PaasClient("http://127.0.0.1:"+server.getAddress().getPort());assertThrows(IllegalArgumentException.class,()->client.putBinary("/files/uploads/x/content","trusted",file,2,1));assertEquals(0,calls.get());}finally{server.stop(0);}
 }
 @Test void binaryJsonResponseIsStillLimitedToOneMiB()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{read(e.getRequestBody());byte[] out=new byte[1024*1024+1];java.util.Arrays.fill(out,(byte)' ');e.sendResponseHeaders(200,out.length);try{e.getResponseBody().write(out);}finally{e.close();}});server.start();
  try{Path file=temp.resolve("large-response");Files.write(file,new byte[]{1});PaasClient client=new PaasClient("http://127.0.0.1:"+server.getAddress().getPort());assertThrows(com.medcloud.course.portal.web.PaasUnavailableException.class,()->client.putBinary("/files/uploads/x/content","trusted",file,2,2));}finally{server.stop(0);}
 }
 @Test void slowChunkedResponseCannotExtendTotalDeadline()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{read(e.getRequestBody());e.sendResponseHeaders(200,0);try{for(int i=0;i<30;i++){e.getResponseBody().write(' ');e.getResponseBody().flush();try{Thread.sleep(150);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}}}catch(java.io.IOException closed){}finally{e.close();}});server.start();
  try{Path file=temp.resolve("slow");Files.write(file,new byte[]{1});PaasClient client=new PaasClient("http://127.0.0.1:"+server.getAddress().getPort());long start=System.nanoTime();assertThrows(com.medcloud.course.portal.web.PaasUnavailableException.class,()->client.putBinary("/files/uploads/x/content","trusted",file,2,1));assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<3500,"total deadline must bound trickle response");}finally{server.stop(0);}
 }
 @Test void redirectCannotForwardSessionToken()throws Exception {
  java.util.concurrent.atomic.AtomicInteger leaked=new java.util.concurrent.atomic.AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/files",e->{read(e.getRequestBody());e.getResponseHeaders().add("Location","/other");e.sendResponseHeaders(307,-1);e.close();});server.createContext("/other",e->{leaked.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});server.start();
  try{Path file=temp.resolve("redirect");Files.write(file,new byte[]{1});PaasClient client=new PaasClient("http://127.0.0.1:"+server.getAddress().getPort());assertThrows(com.medcloud.course.portal.web.PaasRejectedException.class,()->client.putBinary("/files/uploads/x/content","trusted",file,2,1));assertEquals(0,leaked.get());}finally{server.stop(0);}
 }
 @Test void genericJson503KeepsCoreServiceWording()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{e.sendResponseHeaders(503,-1);e.close();});server.start();
  try{PaasClient client=new PaasClient("http://127.0.0.1:"+server.getAddress().getPort());com.medcloud.course.portal.web.PaasRejectedException failure=assertThrows(com.medcloud.course.portal.web.PaasRejectedException.class,()->client.get("/doctor/appointments","trusted"));assertEquals("诊疗服务暂时不可用，请稍后重试",failure.getMessage());}finally{server.stop(0);}
 }
 static byte[] read(java.io.InputStream in)throws java.io.IOException{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
}
