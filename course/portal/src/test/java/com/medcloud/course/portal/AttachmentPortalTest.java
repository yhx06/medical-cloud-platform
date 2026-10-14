package com.medcloud.course.portal;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import com.medcloud.course.contract.Role;
import com.medcloud.course.portal.web.PaasClient;
import com.sun.net.httpserver.HttpServer;
import org.springframework.mock.web.*;
import javax.servlet.http.HttpServlet;
import java.nio.file.*;
import java.net.*;
class AttachmentPortalTest {
 @org.junit.jupiter.api.io.TempDir Path temp;
 static final String ID=AttachmentMultipartTest.ID, OP="22222222-2222-2222-2222-222222222222";
 HttpServlet servlet(String base)throws Exception {
  Class<?> type=assertDoesNotThrow(()->Class.forName("com.medcloud.course.portal.web.AttachmentServlet"));
  return (HttpServlet)type.getConstructor(PaasClient.class,Path.class,long.class,Set.class).newInstance(new PaasClient(base),temp,10L,Collections.singleton("https://files.example.test"));
 }
 MockHttpServletRequest request(String path,Role role){MockHttpServletRequest r=new MockHttpServletRequest("POST",path);r.setServletPath(path);r.setContextPath("/portal");r.getSession().setAttribute("role",role);r.getSession().setAttribute("accessToken","session-token");r.getSession().setAttribute("csrfToken","csrf");return r;}
 @Test void roleCheckBeforeMultipartRead()throws Exception {
  HttpServlet s=servlet("http://127.0.0.1:1/api");
  MockHttpServletRequest r=new MockHttpServletRequest("POST","/patient/files/upload") {
   public Collection<javax.servlet.http.Part> getParts(){fail("unauthorized multipart read");return null;}
   public String getParameter(String name){fail("unauthorized parameter read");return null;}
  };r.setServletPath("/patient/files/upload");r.getSession().setAttribute("role",Role.HOSPITAL_ADMIN);r.getSession().setAttribute("accessToken","token");
  MockHttpServletResponse response=new MockHttpServletResponse();s.service(r,response);assertEquals(403,response.getStatus());
 }
 @Test void missingOrDuplicateCsrfRejected()throws Exception {
  HttpServlet s=servlet("http://127.0.0.1:1/api");
  for(String[] values:new String[][]{new String[0],{"csrf","csrf"},{"bad"}}){MockHttpServletRequest r=request("/files/download",Role.PATIENT);if(values.length>0)r.addParameter("csrfToken",values);r.addParameter("fileId",ID);MockHttpServletResponse response=new MockHttpServletResponse();s.service(r,response);assertEquals(403,response.getStatus());}
 }
 @Test void multipartCsrfFailuresProduce403WithoutBackend()throws Exception {
  HttpServlet s=servlet("http://127.0.0.1:1/api");
  for(int mode=0;mode<3;mode++){MockHttpServletRequest r=request("/patient/files/upload",Role.PATIENT);r.setContentType("multipart/form-data; boundary=test");for(javax.servlet.http.Part part:new AttachmentMultipartTest().request().getParts())if(mode!=0||!"csrfToken".equals(part.getName()))r.addPart(part);if(mode==1)r.addPart(new AttachmentMultipartTest.MemoryPart("csrfToken","csrf".getBytes("UTF-8")));if(mode==2)r.getSession().setAttribute("csrfToken","different");MockHttpServletResponse response=new MockHttpServletResponse();s.service(r,response);assertEquals(403,response.getStatus());try(java.util.stream.Stream<Path> files=Files.list(temp)){assertEquals(0,files.count());}}
 }
 @Test void recoveryControlSurvivesListFailure()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api",e->{e.sendResponseHeaders(503,-1);e.close();});server.start();
  try{HttpServlet s=servlet("http://127.0.0.1:"+server.getAddress().getPort()+"/api");MockHttpServletRequest r=request("/patient/files",Role.PATIENT);r.setMethod("GET");r.addParameter("appointmentId",ID);r.getSession().setAttribute("attachmentUploadKey",ID);r.getSession().setAttribute("attachmentUploadId",OP);r.getSession().setAttribute("attachmentTargetId",ID);MockHttpServletResponse response=new MockHttpServletResponse();s.service(r,response);assertEquals("/WEB-INF/views/attachments.jsp",response.getForwardedUrl());assertEquals(OP,r.getAttribute("uploadId"));assertNotNull(r.getAttribute("attachmentError"));}finally{server.stop(0);}
 }
 @Test void definitelyRejectedPrepareDoesNotTrapFutureUploads()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api",e->{AttachmentClientTest.read(e.getRequestBody());e.sendResponseHeaders(403,-1);e.close();});server.start();
  try{HttpServlet s=servlet("http://127.0.0.1:"+server.getAddress().getPort()+"/api");MockHttpServletRequest r=request("/patient/files/upload",Role.PATIENT);r.setContentType("multipart/form-data; boundary=test");for(javax.servlet.http.Part part:new AttachmentMultipartTest().request().getParts())r.addPart(part);MockHttpServletResponse response=new MockHttpServletResponse();s.service(r,response);assertNull(r.getSession().getAttribute("attachmentUploadKey"));assertNull(r.getSession().getAttribute("attachmentTargetId"));assertEquals(303,response.getStatus());try(java.util.stream.Stream<Path> files=Files.list(temp)){assertEquals(0,files.count());}}finally{server.stop(0);}
 }
 @Test void rejectionAfterUnknownPrepareKeepsOriginalKey()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api",e->{AttachmentClientTest.read(e.getRequestBody());e.sendResponseHeaders(403,-1);e.close();});server.start();
  try{HttpServlet s=servlet("http://127.0.0.1:"+server.getAddress().getPort()+"/api");MockHttpServletRequest r=request("/patient/files/upload",Role.PATIENT);r.getSession().setAttribute("attachmentUploadKey",ID);r.getSession().setAttribute("attachmentTargetId",ID);r.getSession().setAttribute("attachmentUploadState","PENDING");r.setContentType("multipart/form-data; boundary=test");for(javax.servlet.http.Part part:new AttachmentMultipartTest().request().getParts())r.addPart(part);MockHttpServletResponse response=new MockHttpServletResponse();s.service(r,response);assertEquals(ID,r.getSession().getAttribute("attachmentUploadKey"));assertEquals(ID,r.getSession().getAttribute("attachmentTargetId"));}finally{server.stop(0);}
 }
 @Test void noJsUploadAndDeleteUsePrgAndTrustedSessionToken()throws Exception {
  List<String> calls=new ArrayList<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api",e->{calls.add(e.getRequestMethod()+" "+e.getRequestURI()+" "+e.getRequestHeaders().getFirst("Authorization"));String body=new String(AttachmentClientTest.read(e.getRequestBody()),"UTF-8");if(e.getRequestURI().toString().endsWith("/uploads")){assertTrue(body.contains(ID));assertFalse(body.contains("tenantId"));}String data=e.getRequestURI().toString().endsWith("/delete")?"{\"state\":\"DELETE_PENDING\"}":"{\"uploadId\":\""+OP+"\",\"state\":\"AVAILABLE\"}";byte[] out=("{\"code\":\"OK\",\"data\":"+data+"}").getBytes("UTF-8");e.sendResponseHeaders(200,out.length);e.getResponseBody().write(out);e.close();});server.start();
  try {HttpServlet s=servlet("http://127.0.0.1:"+server.getAddress().getPort()+"/api");MockHttpServletRequest r=request("/patient/files/upload",Role.PATIENT);r.setContentType("multipart/form-data; boundary=test");for(javax.servlet.http.Part p:new AttachmentMultipartTest().request().getParts())r.addPart(p);r.addHeader("Authorization","Bearer attacker");MockHttpServletResponse response=new MockHttpServletResponse();s.service(r,response);assertEquals(303,response.getStatus());assertTrue(response.getHeader("Location").startsWith("/portal/patient/files?"));assertEquals(2,calls.size());assertTrue(calls.get(1).startsWith("PUT /api/files/uploads/"+OP+"/content"));assertTrue(calls.stream().allMatch(c->c.endsWith("Bearer session-token")));try(java.util.stream.Stream<Path> files=Files.list(temp)){assertEquals(0,files.count());}
   MockHttpServletRequest d=request("/patient/files/delete",Role.PATIENT);d.addParameter("csrfToken","csrf");d.addParameter("appointmentId",ID);d.addParameter("fileId",OP);MockHttpServletResponse dr=new MockHttpServletResponse();s.service(d,dr);assertEquals(303,dr.getStatus());assertTrue(String.valueOf(d.getSession().getAttribute("attachmentNotice")).contains("清理待重试"));
  }finally{server.stop(0);}
 }
 @Test void signedUrlNeverInFlashLocationOrList()throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api",e->{byte[] out=("{\"code\":\"OK\",\"data\":{\"fileId\":\""+ID+"\",\"url\":\"https://files.example.test/object?signature=fake\",\"expiresAt\":\"2030-01-01T00:05:00Z\"}}").getBytes("UTF-8");e.sendResponseHeaders(200,out.length);e.getResponseBody().write(out);e.close();});server.start();
  try{HttpServlet s=servlet("http://127.0.0.1:"+server.getAddress().getPort()+"/api");MockHttpServletRequest r=request("/files/download",Role.PATIENT);r.addParameter("csrfToken","csrf");r.addParameter("fileId",ID);MockHttpServletResponse response=new MockHttpServletResponse();s.service(r,response);assertEquals("/WEB-INF/views/attachment-download.jsp",response.getForwardedUrl());assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));assertNull(response.getHeader("Location"));for(String name:Collections.list(r.getSession().getAttributeNames()))assertFalse(String.valueOf(r.getSession().getAttribute(name)).contains("signature="));}finally{server.stop(0);}
 }
 @Test void timeoutKeepsOriginalUploadKeyAndOperationIdAndStatusRecovers()throws Exception {
  List<String> calls=Collections.synchronizedList(new ArrayList<>());HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newCachedThreadPool();server.setExecutor(workers);
  server.createContext("/api",e->{calls.add(e.getRequestMethod()+" "+e.getRequestURI());AttachmentClientTest.read(e.getRequestBody());if("PUT".equals(e.getRequestMethod())){try{Thread.sleep(2500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}e.close();return;}String state="POST".equals(e.getRequestMethod())?"RESERVED":"AVAILABLE";reply(e,"{\"uploadId\":\""+OP+"\",\"state\":\""+state+"\"}");});server.start();
  try{
   Class<?> type=Class.forName("com.medcloud.course.portal.web.AttachmentServlet");
   Constructor<?> ctor=assertDoesNotThrow(()->type.getConstructor(PaasClient.class,Path.class,long.class,Set.class,int.class,int.class,int.class));
   HttpServlet s=(HttpServlet)ctor.newInstance(new PaasClient("http://127.0.0.1:"+server.getAddress().getPort()+"/api"),temp,10L,Collections.singleton("https://files.example.test"),120,1,4);
   MockHttpServletRequest r=request("/patient/files/upload",Role.PATIENT);r.setContentType("multipart/form-data; boundary=test");for(javax.servlet.http.Part p:new AttachmentMultipartTest().request().getParts())r.addPart(p);
   MockHttpServletResponse response=new MockHttpServletResponse();s.service(r,response);assertEquals(303,response.getStatus());assertEquals(ID,r.getSession().getAttribute("attachmentUploadKey"));assertEquals(OP,r.getSession().getAttribute("attachmentUploadId"));assertTrue(r.getSession().getAttribute("attachmentNotice").toString().contains("结果待确认"));
   try(java.util.stream.Stream<Path> files=Files.list(temp)){assertEquals(0,files.count());}
   MockHttpServletRequest status=request("/files/upload-status",Role.PATIENT);status.setSession((MockHttpSession)r.getSession());status.addParameter("csrfToken","csrf");MockHttpServletResponse sr=new MockHttpServletResponse();s.service(status,sr);assertEquals(303,sr.getStatus());assertNull(status.getSession().getAttribute("attachmentUploadKey"));assertEquals("GET /api/files/uploads/"+OP,calls.get(calls.size()-1));assertEquals(3,calls.size());
  }finally{server.stop(0);workers.shutdownNow();}
 }
 @Test void patientStateAndFrozenDoctorHistoryGateWritesWithoutLeakingSignedFields()throws Exception {
  java.util.concurrent.atomic.AtomicReference<String> state=new java.util.concurrent.atomic.AtomicReference<>("IN_PROGRESS");java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api",e->{String path=e.getRequestURI().getPath(),data;
   if(path.endsWith("/files"))data="{\"items\":[{\"fileId\":\""+OP+"\",\"sourceType\":\"PATIENT_APPOINTMENT\",\"fileName\":\"demo.pdf\",\"sizeBytes\":3,\"contentType\":\"application/pdf\",\"createdAt\":\"2030-01-01\",\"url\":\"https://files.example.test?secret=fake\",\"objectKey\":\"private\"}],\"total\":21,\"page\":2,\"pageSize\":20}";
   else if(path.endsWith("/records"))data="[{\"id\":\""+OP+"\",\"appointmentId\":\""+ID+"\"}]";
   else if(path.endsWith("/context"))data="{\"id\":\""+ID+"\",\"name\":\"demo\",\"status\":\""+(active.get()?"ACTIVE":"DISABLED")+"\",\"businessWritable\":"+active.get()+"}";
   else data="[{\"id\":\""+ID+"\",\"status\":\""+state.get()+"\",\"tenantStatus\":\""+(active.get()?"ACTIVE":"DISABLED")+"\",\"businessWritable\":"+active.get()+"}]";reply(e,data);});server.start();
  try {com.medcloud.course.portal.web.AttachmentWorkspace workspace=new com.medcloud.course.portal.web.AttachmentWorkspace(new PaasClient("http://127.0.0.1:"+server.getAddress().getPort()+"/api"));
   MockHttpServletRequest p=new MockHttpServletRequest();p.addParameter("appointmentId",ID);p.addParameter("page","2");workspace.load(p,"session-token",Role.PATIENT);assertEquals(true,p.getAttribute("attachmentWritable"));assertEquals(false,p.getAttribute("attachmentDeleteAllowed"));assertFalse(p.getAttribute("attachmentPage").toString().contains("secret"));assertFalse(p.getAttribute("attachmentPage").toString().contains("objectKey"));
   state.set("COMPLETED");workspace.load(p,"session-token",Role.PATIENT);assertEquals(false,p.getAttribute("attachmentWritable"));
   MockHttpServletRequest d=new MockHttpServletRequest();d.addParameter("recordId",OP);d.addParameter("page","2");workspace.load(d,"session-token",Role.DOCTOR);assertEquals(true,d.getAttribute("attachmentWritable"));assertEquals(OP,d.getAttribute("targetId"));active.set(false);workspace.load(d,"session-token",Role.DOCTOR);assertEquals(false,d.getAttribute("attachmentWritable"));assertNotNull(d.getAttribute("attachmentPage"));
  }finally{server.stop(0);}
 }
 @Test void initializerRegistersOnlyExactRoutesAndBoundsMultipartAndDegradesInvalidConfig()throws Exception {
  Map<String,String> config=new HashMap<>();config.put("COURSE_ATTACHMENTS_ENABLED","true");config.put("COURSE_ATTACHMENTS_MAX_BYTES","100");config.put("COURSE_ATTACHMENTS_TEMP_DIR",temp.resolve("spool").toString());config.put("COURSE_ATTACHMENTS_DOWNLOAD_ORIGINS","https://files.example.test");
  List<String> routes=new ArrayList<>();java.util.concurrent.atomic.AtomicReference<javax.servlet.MultipartConfigElement> multipart=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicReference<HttpServlet> registered=new java.util.concurrent.atomic.AtomicReference<>();
  javax.servlet.ServletRegistration.Dynamic registration=(javax.servlet.ServletRegistration.Dynamic)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{javax.servlet.ServletRegistration.Dynamic.class},(proxy,m,args)->{if("addMapping".equals(m.getName())){routes.addAll(Arrays.asList((String[])args[0]));return Collections.emptySet();}if("setMultipartConfig".equals(m.getName()))multipart.set((javax.servlet.MultipartConfigElement)args[0]);return null;});
  javax.servlet.ServletContext context=(javax.servlet.ServletContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{javax.servlet.ServletContext.class},(proxy,m,args)->{if("getInitParameter".equals(m.getName()))return config.get(args[0]);if("addServlet".equals(m.getName())){registered.set((HttpServlet)args[1]);return registration;}return null;});
  new com.medcloud.course.portal.web.AttachmentServletInitializer().onStartup(Collections.emptySet(),context);assertNotNull(multipart.get());assertEquals(100,multipart.get().getMaxFileSize());assertEquals(65636,multipart.get().getMaxRequestSize());assertEquals(temp.resolve("spool").toRealPath().toString(),multipart.get().getLocation());assertEquals(0,multipart.get().getFileSizeThreshold());assertEquals(new HashSet<>(Arrays.asList("/patient/files","/doctor/files","/patient/files/upload","/doctor/files/upload","/patient/files/delete","/files/download","/files/upload-status")),new HashSet<>(routes));
  boolean discovered=false;for(javax.servlet.ServletContainerInitializer initializer:ServiceLoader.load(javax.servlet.ServletContainerInitializer.class))if(initializer instanceof com.medcloud.course.portal.web.AttachmentServletInitializer)discovered=true;assertTrue(discovered,"WAR must expose the initializer through the standard service provider entry");
  config.put("COURSE_ATTACHMENTS_MAX_BYTES","0");new com.medcloud.course.portal.web.AttachmentServletInitializer().onStartup(Collections.emptySet(),context);MockHttpServletRequest r=request("/patient/files",Role.PATIENT);r.setMethod("GET");MockHttpServletResponse s=new MockHttpServletResponse();registered.get().service(r,s);assertEquals("/WEB-INF/views/attachments.jsp",s.getForwardedUrl());assertTrue(String.valueOf(r.getAttribute("attachmentError")).contains("配置无效"));
 }
 static void reply(com.sun.net.httpserver.HttpExchange e,String data)throws java.io.IOException{byte[] out=("{\"code\":\"OK\",\"data\":"+data+"}").getBytes("UTF-8");e.sendResponseHeaders(200,out.length);e.getResponseBody().write(out);e.close();}
 @Test void userinfoFragmentOrWrongOriginDownloadUrlRejected() throws Exception {
  Class<?> policy=assertDoesNotThrow(()->Class.forName("com.medcloud.course.portal.web.AttachmentDownloadPolicy"));
  Method method=policy.getMethod("requireAllowed",String.class,Set.class);
  Set<String> origins=Collections.singleton("https://files.example.test");
  assertEquals("https://files.example.test/object?signature=fake",method.invoke(null,"https://files.example.test/object?signature=fake",origins).toString());
  for(String url:Arrays.asList("https://user@files.example.test/object","https://files.example.test/object#x","https://evil.test/object","javascript:alert(1)","//files.example.test/object")){
   InvocationTargetException error=assertThrows(InvocationTargetException.class,()->method.invoke(null,url,origins));assertTrue(error.getCause() instanceof IllegalArgumentException);
  }
 }
}
