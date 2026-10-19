package com.medcloud.course.portal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import com.medcloud.course.portal.web.PaasClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
@ResourceLock("portal-upload-deadline-executor")
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
 @Test void binaryResponseDeadlineHoldsWhenWatchdogIsBlocked()throws Exception {
  java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.atomic.AtomicInteger aborted=new java.util.concurrent.atomic.AtomicInteger();
  java.util.concurrent.atomic.AtomicBoolean trickle=new java.util.concurrent.atomic.AtomicBoolean(true);
  java.util.concurrent.ExecutorService serverThreads=serverExecutor();
  HttpServer server=binaryServer(trickle,requests,aborted,serverThreads);java.util.concurrent.ScheduledThreadPoolExecutor deadlines=deadlineExecutor();
  java.util.concurrent.CountDownLatch occupied=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
  try{
   awaitIdleWatchdog(deadlines);
   deadlines.execute(()->{occupied.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
   assertTrue(occupied.await(2,java.util.concurrent.TimeUnit.SECONDS),"the single watchdog thread must be occupied before the upload");
   Path file=temp.resolve("blocked-watchdog");Files.write(file,new byte[]{1});
   PaasClient client=new PaasClient("http://127.0.0.1:"+server.getAddress().getPort());
   long start=System.nanoTime();
   assertThrows(com.medcloud.course.portal.web.PaasUnavailableException.class,()->client.putBinary("/files/uploads/x/content","trusted",file,2,1));
   long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
   assertTrue(elapsed<1800,"binary reader must enforce its own absolute deadline while the watchdog is blocked, elapsed="+elapsed);
   long waitUntil=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
   while(aborted.get()==0&&System.nanoTime()<waitUntil)Thread.sleep(25);
   assertTrue(aborted.get()>=1,"server must observe the aborted response body");
   release.countDown();
   java.util.concurrent.CountDownLatch tick=new java.util.concurrent.CountDownLatch(1);
   deadlines.schedule(tick::countDown,10,java.util.concurrent.TimeUnit.MILLISECONDS);
   assertTrue(tick.await(2,java.util.concurrent.TimeUnit.SECONDS),"watchdog executor must recover after the blocked call");
   trickle.set(false);Path recovered=temp.resolve("recovered");Files.write(recovered,new byte[]{9});
   assertNotNull(client.putBinary("/files/uploads/x/content","trusted",recovered,2,2),"a normal binary upload must still succeed");
   assertEquals(2,requests.get());
  }finally{release.countDown();server.stop(0);serverThreads.shutdownNow();}
 }
 @Test void concurrentBinaryUploadsBothExpireWhenWatchdogIsBlocked()throws Exception {
  java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();java.util.concurrent.atomic.AtomicInteger aborted=new java.util.concurrent.atomic.AtomicInteger();
  java.util.concurrent.atomic.AtomicBoolean trickle=new java.util.concurrent.atomic.AtomicBoolean(true);
  java.util.concurrent.ExecutorService serverThreads=serverExecutor();
  HttpServer server=binaryServer(trickle,requests,aborted,serverThreads);java.util.concurrent.ScheduledThreadPoolExecutor deadlines=deadlineExecutor();
  java.util.concurrent.CountDownLatch occupied=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
  try{
   awaitIdleWatchdog(deadlines);
   deadlines.execute(()->{occupied.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
   assertTrue(occupied.await(2,java.util.concurrent.TimeUnit.SECONDS),"the single watchdog thread must be occupied before the uploads");
   java.util.concurrent.atomic.AtomicLong[] elapsed={new java.util.concurrent.atomic.AtomicLong(),new java.util.concurrent.atomic.AtomicLong()};
   java.util.concurrent.atomic.AtomicInteger slot=new java.util.concurrent.atomic.AtomicInteger();
   java.util.concurrent.atomic.AtomicInteger failures=new java.util.concurrent.atomic.AtomicInteger();
   java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
   Runnable upload=()->{int index=slot.getAndIncrement();try{Path file=temp.resolve("concurrent-"+Thread.currentThread().getId());Files.write(file,new byte[]{1});
    PaasClient client=new PaasClient("http://127.0.0.1:"+server.getAddress().getPort());start.await();
    long begin=System.nanoTime();
    try{assertThrows(com.medcloud.course.portal.web.PaasUnavailableException.class,()->client.putBinary("/files/uploads/x/content","trusted",file,2,1));}
    finally{elapsed[index].set(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin));}
   }catch(Throwable ex){failures.incrementAndGet();}};
   Thread a=new Thread(upload,"binary-a"),b=new Thread(upload,"binary-b");a.start();b.start();start.countDown();a.join(10000);b.join(10000);
   assertFalse(a.isAlive()||b.isAlive(),"both callers must return within their bound");
   assertEquals(0,failures.get());
   assertTrue(elapsed[0].get()>0&&elapsed[1].get()>0);
   long later=Math.max(elapsed[0].get(),elapsed[1].get()),earlier=Math.min(elapsed[0].get(),elapsed[1].get());
   assertTrue(later<1800,"each concurrent upload must expire on its own deadline, elapsed="+later);
   assertTrue(later-earlier<1200,"neither upload may wait for the other's watchdog slot, gap="+(later-earlier));
   assertEquals(2,requests.get());
   long waitUntil=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
   while(aborted.get()<2&&System.nanoTime()<waitUntil)Thread.sleep(25);
   assertTrue(aborted.get()>=2,"both aborted response bodies must be observed by the server, aborted="+aborted.get());
  }finally{release.countDown();server.stop(0);serverThreads.shutdownNow();}
 }
 static java.util.concurrent.ExecutorService serverExecutor(){
  return java.util.concurrent.Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"fake-binary-server");t.setDaemon(true);return t;});
 }
 static HttpServer binaryServer(java.util.concurrent.atomic.AtomicBoolean trickle,java.util.concurrent.atomic.AtomicInteger requests,java.util.concurrent.atomic.AtomicInteger aborted,java.util.concurrent.ExecutorService serverThreads)throws java.io.IOException{
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.setExecutor(serverThreads);
  server.createContext("/files/uploads/x/content",e->{
   requests.incrementAndGet();read(e.getRequestBody());
   if(!trickle.get()){byte[] out="{\"code\":\"OK\",\"data\":{\"state\":\"AVAILABLE\"}}".getBytes("UTF-8");e.sendResponseHeaders(200,out.length);e.getResponseBody().write(out);e.close();return;}
   e.sendResponseHeaders(200,0);
   try{for(int i=0;i<40;i++){e.getResponseBody().write(' ');e.getResponseBody().flush();Thread.sleep(150);}}
   catch(java.io.IOException closed){aborted.incrementAndGet();}
   catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
   finally{e.close();}
  });
  server.start();return server;
 }
 static void awaitIdleWatchdog(java.util.concurrent.ScheduledThreadPoolExecutor deadlines)throws Exception{
  java.util.concurrent.CountDownLatch probe=new java.util.concurrent.CountDownLatch(1);
  deadlines.schedule(probe::countDown,1,java.util.concurrent.TimeUnit.MILLISECONDS);
  assertTrue(probe.await(5,java.util.concurrent.TimeUnit.SECONDS),"the shared watchdog executor must be idle before this test occupies it");
 }
 static java.util.concurrent.ScheduledThreadPoolExecutor deadlineExecutor()throws Exception{
  java.lang.reflect.Field field=PaasClient.class.getDeclaredField("DEADLINES");field.setAccessible(true);
  return (java.util.concurrent.ScheduledThreadPoolExecutor)field.get(null);
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
