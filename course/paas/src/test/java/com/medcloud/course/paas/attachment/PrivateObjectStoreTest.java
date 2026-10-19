package com.medcloud.course.paas.attachment;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.medcloud.course.paas.sdk.internal.minio.*;
import com.medcloud.course.paas.sdk.internal.minio.errors.ErrorResponseException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrivateObjectStoreTest {
    @Test void keysCannotEscapeConfiguredPrefix() {
        PrivateObjectStore store=AttachmentConfiguration.fromEnvironment(AttachmentConfigurationTest.valid()).createStore();
        for(String key:new String[]{"other/key","course-attachments/../key","course-attachments/患者","course-attachments/"})
            assertEquals(400,assertThrows(AttachmentException.class,()->store.head(key)).getStatus());
    }
    @Test void rootCredentialsNotUsedByApplication() {
        Map<String,String> e=AttachmentConfigurationTest.valid();
        e.put("MINIO_ROOT_USER","fixture-root-only");e.put("MINIO_ROOT_PASSWORD","fixture-root-secret-only");
        assertTrue(AttachmentConfiguration.fromEnvironment(e).isAvailable());
        e.put("COURSE_S3_ACCESS_KEY","fixture-root-only");
        assertFalse(AttachmentConfiguration.fromEnvironment(e).isAvailable());
    }
    /** Every case must be rejected by its own preflight branch, never by the fake server's unknown-operation fallback. */
    @Test void versionedOrLockedOrPublicBucketRejectedAtTargetPreflightBranch() throws Exception {
        String[][] cases={
            // kind, expected versioning calls, lock calls, policy calls
            {"Enabled","1","0","0"},
            {"Suspended","1","0","0"},
            {"Locked","1","1","0"},
            {"Public","1","1","1"},
            {"PublicArray","1","1","1"},
            {"PublicObjectString","1","1","1"},
            {"PublicEscaped","1","1","1"},
            {"PublicWhitespace","1","1","1"},
            {"PublicNotPrincipal","1","1","1"},
            {"PublicUnparseable","1","1","1"},
            {"PublicUnknownEffect","1","1","1"},
        };
        for(String[] entry:cases) {
            String kind=entry[0];
            FakeS3 fake=new FakeS3(kind);fake.start();
            try(MinioPrivateObjectStore store=store(fake.server,3)) {
                assertEquals(503,assertThrows(AttachmentException.class,()->store.delete("course-attachments/abc")).getStatus(),
                    kind+" must be rejected before a storage mutation");
                assertEquals(0,fake.deletes.get(),kind+": preflight must reject before a storage mutation");
                assertEquals(Integer.parseInt(entry[1]),fake.versioning.get(),kind+": must reach the versioning preflight branch");
                assertEquals(Integer.parseInt(entry[2]),fake.lock.get(),kind+": object-lock preflight branch reach mismatch");
                assertEquals(Integer.parseInt(entry[3]),fake.policy.get(),kind+": policy preflight branch reach mismatch");
                assertEquals(0,fake.unexpected.get(),kind+": UnexpectedOperation fallback must never be the rejection reason");
            } finally {fake.stop();}
        }
    }
    @Test void normalPrivateBucketCompletesDelete() throws Exception {
        FakeS3 fake=new FakeS3("Private");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,3)) {
            store.delete("course-attachments/abc");
            assertEquals(1,fake.deletes.get(),"normal private bucket must complete DELETE");
            assertEquals(0,fake.unexpected.get());
        } finally {fake.stop();}
    }
    @Test void denyStatementWithWildcardPrincipalIsNotAnAnonymousGrant() throws Exception {
        FakeS3 fake=new FakeS3("PrivateDeny");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,3)) {
            store.delete("course-attachments/abc");
            assertEquals(1,fake.policy.get(),"policy must be read before deciding");
            assertEquals(1,fake.deletes.get(),"a Deny statement is not an anonymous grant");
            assertEquals(0,fake.unexpected.get());
        } finally {fake.stop();}
    }
    @Test void listKeysStopsAtLimitWithoutFetchingLaterPages() throws Exception {
        FakeS3 fake=new FakeS3("ListPages");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,3)) {
            assertEquals(Collections.singletonList("course-attachments/aaa"),store.listKeys("course-attachments/",null,1));
            assertEquals(1,fake.listPages.get(),"first list page must be enough for limit=1");
            assertEquals(0,fake.listLaterPages.get(),"reaching the limit must not request later pages");
            assertEquals(0,fake.unexpected.get());
        } finally {fake.stop();}
    }
    @Test void listKeysAdvancesFromAfterKey() throws Exception {
        FakeS3 fake=new FakeS3("ListPages");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,3)) {
            assertEquals(Collections.singletonList("course-attachments/bbb"),store.listKeys("course-attachments/","course-attachments/aaa",1));
            assertEquals("course-attachments/aaa",fake.lastStartAfter.get(),"afterKey must be forwarded to storage");
            assertEquals(0,fake.listLaterPages.get());
        } finally {fake.stop();}
    }
    @Test void storeTotalDeadlineBoundsRetries() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger requests=new AtomicInteger();
        server.createContext("/",exchange->{requests.incrementAndGet();try{Thread.sleep(5000);}catch(InterruptedException ignored){}finally{exchange.close();}});server.start();
        try(MinioPrivateObjectStore store=store(server,1)) {
            long start=System.nanoTime();assertEquals(503,assertThrows(AttachmentException.class,()->store.head("course-attachments/abc")).getStatus());assertTrue((System.nanoTime()-start)/1000000<2500);assertTrue(requests.get()>=1);
        }finally{server.stop(0);}
    }
    @Test void tricklingCallIsAbortedAfterStoreDeadline() throws Exception {
        FakeS3 fake=new FakeS3("Trickle");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,1)) {
            long start=System.nanoTime();
            assertEquals(503,assertThrows(AttachmentException.class,()->store.head("course-attachments/abc")).getStatus());
            assertTrue((System.nanoTime()-start)/1000000<2500,"caller must receive the store deadline error in time");
            long waitUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(fake.writeFailures.get()==0&&System.nanoTime()<waitUntil)Thread.sleep(50);
            assertTrue(fake.writeFailures.get()>=1,"underlying HTTP call must be aborted after the store deadline instead of trickling forever");
        } finally {fake.stop();}
    }
    @Test void expiredDeadlineStopsBeforeNewStorageCalls() throws Exception {
        FakeS3 fake=new FakeS3("SlowPreflight");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,1)) {
            long start=System.nanoTime();
            assertEquals(503,assertThrows(AttachmentException.class,()->store.head("course-attachments/abc")).getStatus());
            assertTrue((System.nanoTime()-start)/1000000<2500);
            Thread.sleep(1500);
            assertEquals(1,fake.requests.get(),"after the deadline no later preflight/storage call may be issued");
            assertEquals(0,fake.lock.get());
            assertEquals(0,fake.unexpected.get());
        } finally {fake.stop();}
    }
    /**
     * Caller-side cancel and drain: each caller owns its deadline and cancels at expiry. Holding the
     * real preflight monitor parks all three attempts until after expiry; after release every attempt
     * must drain without issuing HTTP or object work. This does not by itself prove the deadline guard
     * (caller cancellation also stops work); the waiter tests below cover that separately.
     */
    @Test void cancelledLockWaitersDrainWithoutStorageCalls() throws Exception {
        FakeS3 fake=new FakeS3("SlowPreflight");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,1)) {
            Object preflightLock=field(store,"preflightLock");
            java.util.concurrent.ThreadPoolExecutor pool=(java.util.concurrent.ThreadPoolExecutor)field(store,"calls");
            List<Thread> callers=new ArrayList<>();
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            final CountDownLatch start=new CountDownLatch(1);
            try {
                synchronized(preflightLock) {
                    for(int i=0;i<3;i++) {
                        Thread t=new Thread(()->{
                            try{start.await();assertEquals(503,assertThrows(AttachmentException.class,()->store.head("course-attachments/abc")).getStatus());}
                            catch(Throwable ex){failure.compareAndSet(null,ex);}
                        },"expired-waiter");
                        t.start();callers.add(t);
                    }
                    start.countDown();
                    for(Thread t:callers){t.join(5000);assertFalse(t.isAlive(),"each caller must receive its own bounded deadline error");}
                    assertNull(failure.get(),()->"caller failed unexpectedly: "+failure.get());
                    long activeUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while(pool.getActiveCount()<3&&System.nanoTime()<activeUntil)Thread.sleep(10);
                    assertEquals(3,pool.getActiveCount(),"all three attempts must still be inside the held preflight section");
                    assertEquals(0,fake.requests.get(),"no attempt may reach HTTP while the monitor is held");
                }
            } finally {start.countDown();}
            // Release the monitor, then use the whole pool as a barrier: every canceled waiter must finish.
            CountDownLatch drained=new CountDownLatch(pool.getMaximumPoolSize());
            for(int i=0;i<pool.getMaximumPoolSize();i++)pool.execute(drained::countDown);
            assertTrue(drained.await(5,TimeUnit.SECONDS),"expired attempts must drain from the pool");
            assertEquals(0,fake.requests.get(),"a canceled waiter must not start preflight or object work after the monitor is released");
            assertEquals(0,fake.lock.get());
            assertEquals(0,fake.policy.get());
            assertEquals(0,fake.deletes.get());
            assertEquals(0,fake.unexpected.get());
        } finally {fake.stop();}
    }
    /** Direct guard check: an already expired attempt must be rejected before its first storage call. */
    @Test void expiredPreflightIssuesNoStorageCall() throws Exception {
        FakeS3 fake=new FakeS3("Private");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,3)) {
            java.lang.reflect.Method preflight=MinioPrivateObjectStore.class.getDeclaredMethod("preflight",long.class);
            preflight.setAccessible(true);
            java.lang.reflect.InvocationTargetException rejected=assertThrows(java.lang.reflect.InvocationTargetException.class,
                ()->preflight.invoke(store,System.nanoTime()-1));
            assertTrue(rejected.getCause() instanceof AttachmentException,"expired preflight must fail closed");
            assertEquals(503,((AttachmentException)rejected.getCause()).getStatus());
            assertEquals(0,fake.requests.get(),"expired preflight must not issue any HTTP request");
        } finally {fake.stop();}
    }
    /**
     * A waiter that expires while blocked on the held monitor, with no caller-side cancellation:
     * after release it must fail closed instead of starting preflight HTTP.
     */
    @Test void waiterExpiringOnHeldPreflightIssuesNoStorageCall() throws Exception {
        FakeS3 fake=new FakeS3("Private");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,3)) {
            Object preflightLock=field(store,"preflightLock");
            java.lang.reflect.Method preflight=MinioPrivateObjectStore.class.getDeclaredMethod("preflight",long.class);
            preflight.setAccessible(true);
            final java.util.concurrent.atomic.AtomicReference<Throwable> outcome=new java.util.concurrent.atomic.AtomicReference<>();
            final CountDownLatch submitted=new CountDownLatch(1);
            Thread waiter=new Thread(()->{
                submitted.countDown();
                try{preflight.invoke(store,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(300));outcome.set(new AssertionError("expired waiter returned normally"));}
                catch(java.lang.reflect.InvocationTargetException ex){outcome.set(ex.getCause());}
                catch(Throwable ex){outcome.set(ex);}
            },"preflight-waiter");
            synchronized(preflightLock) {
                waiter.start();
                assertTrue(submitted.await(2,TimeUnit.SECONDS));
                Thread.sleep(600);
                assertEquals(0,fake.requests.get(),"a waiter must issue no HTTP while the monitor is held");
            }
            waiter.join(5000);
            assertFalse(waiter.isAlive(),"the expired waiter must finish after the monitor is released");
            assertTrue(outcome.get() instanceof AttachmentException,()->"waiter outcome: "+outcome.get());
            assertEquals(503,((AttachmentException)outcome.get()).getStatus());
            assertEquals(0,fake.requests.get(),"an expired waiter must not start preflight HTTP after release");
        } finally {fake.stop();}
    }
    @Test void signedExpiryMatchesActualSignatureMoment() throws Exception {
        FakeS3 fake=new FakeS3("SlowPreflight");fake.start();
        try(MinioPrivateObjectStore store=store(fake.server,10)) {
            Instant issuedAt=Instant.now();
            StoreDownload download=store.signGet(new AttachmentSnapshot("fixture-file","course-attachments/abc","fixture.bin","application/octet-stream",1),issuedAt,300);
            Instant signatureMoment=signatureMoment(download.getUrl());
            assertEquals(signatureMoment.plusSeconds(300),download.getExpiresAt(),"expiresAt must be the actual signing moment plus the TTL");
            assertTrue(download.getExpiresAt().isAfter(issuedAt.plusSeconds(301)),
                "slow preflight must not report the earlier issuedAt based expiry");
            assertTrue(download.getUrl().contains("X-Amz-Expires=300"));
        } finally {fake.stop();}
    }
    @Test void realPutHeadListAndDeleteUsesApplicationCredentials() throws Exception {
        withRealObject((fixture,store,key,bytes,sha)->{
            StoreObjectInfo info=store.head(key);assertNotNull(info);assertEquals(bytes.length,info.getSizeBytes());assertEquals(sha,info.getSha256());
            assertTrue(store.listKeys(fixture.prefix,null,100).contains(key));
            store.delete(key);assertNull(store.head(key));assertFalse(store.listKeys(fixture.prefix,null,100).contains(key));
        });
    }
    @Test void bucketMetadataPermissionsPresentForPreflight() throws Exception {
        Fixture f=Fixture.fromEnvironment();assumeTrue(f!=null,"COURSE_ATTACHMENTS_FIXTURE is required for real MinIO evidence");
        try(MinioClient client=MinioClient.builder().endpoint(f.internalEndpoint).region("us-east-1").credentials(f.applicationAccessKey,f.applicationSecretKey).build()) {
            client.setTimeout(3000,10000,10000);
            try {client.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(f.bucket).build());}
            catch(ErrorResponseException ex){fail(safeMetadataFailure("GetBucketVersioning",ex));}
            try {client.getObjectLockConfiguration(GetObjectLockConfigurationArgs.builder().bucket(f.bucket).build());}
            catch(ErrorResponseException ex){if(!code(ex,"ObjectLockConfigurationNotFoundError","NoSuchObjectLockConfiguration","NotFound"))fail(safeMetadataFailure("GetBucketObjectLockConfiguration",ex));}
            try {client.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(f.bucket).build());}
            catch(ErrorResponseException ex){if(!code(ex,"NoSuchBucketPolicy","NoSuchPolicy","NotFound"))fail(safeMetadataFailure("GetBucketPolicy",ex));}
        }
    }
    @Test void unsignedGetAndListDenied() throws Exception {
        withRealObject((fixture,store,key,bytes,sha)->{
            assertEquals(403,status(fixture.publicEndpoint+"/"+fixture.bucket+"/"+key));
            String list=fixture.publicEndpoint+"/"+fixture.bucket+"?list-type=2&prefix="+URLEncoder.encode(fixture.prefix,"UTF-8");
            assertEquals(403,status(list));
        });
    }
    @Test void signedGetMatchesDigestAndExpiresAt300Seconds() throws Exception {
        withRealObject((fixture,store,key,bytes,sha)->{
            Instant issuedAt=Instant.now();
            StoreDownload download=store.signGet(new AttachmentSnapshot("fixture-file",key,"fixture.bin","application/octet-stream",1),issuedAt,300);
            assertEquals(signatureMoment(download.getUrl()).plusSeconds(300),download.getExpiresAt(),
                "reported expiry must match the real signature moment plus the TTL");
            URI uri=URI.create(download.getUrl()), expected=URI.create(fixture.publicEndpoint);
            assertEquals(expected.getScheme(),uri.getScheme());assertEquals(expected.getHost(),uri.getHost());assertEquals(expected.getPort(),uri.getPort());
            assertTrue(uri.getRawQuery().contains("X-Amz-Expires=300"));assertArrayEquals(bytes,get(download.getUrl()));assertEquals(sha,digest(get(download.getUrl())));
            long wait=Math.max(0,download.getExpiresAt().plusSeconds(2).toEpochMilli()-System.currentTimeMillis());
            if(wait>0)Thread.sleep(wait);
            assertTrue(status(download.getUrl())>=400,"signed URL must be rejected after its real 300 second lifetime");
        });
    }
    @Test void tamperedSignatureRejected() throws Exception {
        withRealObject((fixture,store,key,bytes,sha)->{
            String signed=store.signGet(new AttachmentSnapshot("fixture-file",key,"fixture.bin","application/octet-stream",1),Instant.now(),300).getUrl();
            Matcher matcher=Pattern.compile("(?i)(X-Amz-Signature=)([0-9a-f])").matcher(signed);assertTrue(matcher.find());
            String replacement="0".equals(matcher.group(2))?"1":"0";
            String tampered=signed.substring(0,matcher.start(2))+replacement+signed.substring(matcher.end(2));
            assertEquals(403,status(tampered));
        });
    }
    private MinioPrivateObjectStore store(HttpServer server,int deadline) {
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        return new MinioPrivateObjectStore(endpoint,endpoint,"us-east-1","course-files","course-test-app","fictional-test-secret","course-attachments/",new AttachmentLimits(10485760,20000000,200,4,2,3,deadline,120));
    }
    private static Object field(Object target,String name) throws Exception {
        java.lang.reflect.Field field=target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private interface RealAssertion {void run(Fixture fixture,MinioPrivateObjectStore store,String key,byte[] bytes,String sha) throws Exception;}
    private void withRealObject(RealAssertion assertion) throws Exception {
        Fixture fixture=Fixture.fromEnvironment();assumeTrue(fixture!=null,"COURSE_ATTACHMENTS_FIXTURE is required for real MinIO evidence");
        Map<String,String> e=new HashMap<>();e.put("COURSE_ATTACHMENTS_ENABLED","true");
        e.put("COURSE_S3_INTERNAL_ENDPOINT",fixture.internalEndpoint);e.put("COURSE_S3_PUBLIC_ENDPOINT",fixture.publicEndpoint);
        e.put("COURSE_S3_REGION","us-east-1");e.put("COURSE_S3_BUCKET",fixture.bucket);e.put("COURSE_S3_PREFIX",fixture.prefix);
        e.put("COURSE_S3_ACCESS_KEY",fixture.applicationAccessKey);e.put("COURSE_S3_SECRET_KEY",fixture.applicationSecretKey);
        AttachmentConfiguration configuration=AttachmentConfiguration.fromEnvironment(e);assertTrue(configuration.isAvailable());
        byte[] bytes=("fictional-task2-object-"+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);String sha=digest(bytes);
        Path path=Files.createTempFile("task2-minio-",".bin");Files.write(path,bytes);
        String key=fixture.prefix+"task2-"+UUID.randomUUID().toString().replace("-","");
        try(MinioPrivateObjectStore store=(MinioPrivateObjectStore)configuration.createStore()) {
            store.put(key,new ValidatedAttachment(path,"fixture.bin","application/octet-stream",bytes.length,sha));
            try {assertion.run(fixture,store,key,bytes,sha);} finally {try{store.delete(key);}catch(RuntimeException ignored){}}
        } finally {Files.deleteIfExists(path);}
    }
    private static Instant signatureMoment(String url) {
        Matcher matcher=Pattern.compile("X-Amz-Date=([0-9]{8}T[0-9]{6}Z)").matcher(url);
        assertTrue(matcher.find(),"presigned URL must carry its signature moment");
        return LocalDateTime.parse(matcher.group(1),DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC);
    }
    private static int status(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URI(url).toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(3000);c.setReadTimeout(10000);
        try{return c.getResponseCode();}finally{c.disconnect();}
    }
    private static byte[] get(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URI(url).toURL().openConnection();c.setConnectTimeout(3000);c.setReadTimeout(10000);
        try {assertEquals(200,c.getResponseCode());try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
        finally{c.disconnect();}
    }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b&255));return s.toString();
    }
    private static boolean code(ErrorResponseException ex,String... expected) {
        String actual=ex.errorResponse()==null?null:ex.errorResponse().code();for(String value:expected)if(value.equals(actual))return true;return false;
    }
    private static String safeMetadataFailure(String operation,ErrorResponseException ex) {
        String code=ex.errorResponse()==null?"missing":ex.errorResponse().code();int status=ex.response()==null?-1:ex.response().code();
        return "bucket metadata preflight denied: operation="+operation+", code="+code+", httpStatus="+status;
    }
    /** Fake S3 endpoint. Query keys are parsed properly so preflight negatives reach their real branches. */
    private static final class FakeS3 {
        final String kind;
        final AtomicInteger versioning=new AtomicInteger(),lock=new AtomicInteger(),policy=new AtomicInteger(),deletes=new AtomicInteger();
        final AtomicInteger unexpected=new AtomicInteger(),requests=new AtomicInteger(),listPages=new AtomicInteger(),listLaterPages=new AtomicInteger();
        final AtomicInteger writeFailures=new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<String> lastStartAfter=new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server;
        FakeS3(String kind){this.kind=kind;}
        void start() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/",this::handle);
            server.start();
        }
        void stop(){server.stop(0);}
        void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            Map<String,String> q=query(exchange.getRequestURI());
            int code=200;String body="";boolean json=false;
            try {
                if("Trickle".equals(kind)) {
                    // Valid but never-completing XML: the client parse stays blocked on a live call,
                    // so only a real call deadline/cancel can reclaim it.
                    exchange.getResponseHeaders().add("Content-Type","application/xml");
                    exchange.sendResponseHeaders(200,0);
                    OutputStream out=exchange.getResponseBody();
                    out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    long stopAt=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
                    try {
                        while(System.nanoTime()<stopAt) {out.write("<!--x-->".getBytes(StandardCharsets.UTF_8));out.flush();Thread.sleep(120);}
                    } catch(IOException ex) {writeFailures.incrementAndGet();}
                    catch(InterruptedException ex) {Thread.currentThread().interrupt();}
                    finally {try{exchange.close();}catch(Exception ignored){}}
                    return;
                }
                if(q.containsKey("versioning")) {
                    if("SlowPreflight".equals(kind)) {
                        try{Thread.sleep(2500);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
                    }
                    versioning.incrementAndGet();
                    body="<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"+
                        ("Enabled".equals(kind)||"Suspended".equals(kind)?"<Status>"+kind+"</Status>":"")+"</VersioningConfiguration>";
                } else if(q.containsKey("object-lock")) {
                    lock.incrementAndGet();
                    if("Locked".equals(kind)) {
                        body="<ObjectLockConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><ObjectLockEnabled>Enabled</ObjectLockEnabled></ObjectLockConfiguration>";
                    } else {code=404;body="<Error><Code>ObjectLockConfigurationNotFoundError</Code><Message>none</Message></Error>";}
                } else if(q.containsKey("policy")) {
                    policy.incrementAndGet();
                    body=policyBody();json=body!=null;
                    if(body==null) {code=404;body="<Error><Code>NoSuchBucketPolicy</Code><Message>none</Message></Error>";}
                } else if(q.containsKey("list-type")) {
                    if("Trickle".equals(kind)||"SlowPreflight".equals(kind)) {unexpected.incrementAndGet();code=500;body="<Error><Code>UnexpectedOperation</Code><Message>blocked</Message></Error>";}
                    else if("ListPages".equals(kind)) {
                        String token=q.get("continuation-token");
                        lastStartAfter.set(q.get("start-after"));
                        if(token!=null&&!token.isEmpty()) {
                            listLaterPages.incrementAndGet();code=500;
                            body="<Error><Code>SecondPageMustNotBeRequested</Code><Message>blocked</Message></Error>";
                        } else {
                            listPages.incrementAndGet();
                            String key="course-attachments/aaa";
                            if(q.get("start-after")!=null&&!q.get("start-after").isEmpty())key="course-attachments/bbb";
                            String next=key.equals("course-attachments/aaa")?"<IsTruncated>true</IsTruncated><NextContinuationToken>next-page-token</NextContinuationToken>":"<IsTruncated>false</IsTruncated>";
                            body="<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>course-files</Name><Prefix>course-attachments/</Prefix><KeyCount>1</KeyCount><MaxKeys>1</MaxKeys>"+
                                next+"<Contents><Key>"+key+"</Key><LastModified>2026-10-15T00:00:00.000Z</LastModified><ETag>\"abc\"</ETag><Size>1</Size><StorageClass>STANDARD</StorageClass></Contents></ListBucketResult>";
                        }
                    } else {unexpected.incrementAndGet();code=500;body="<Error><Code>UnexpectedOperation</Code><Message>blocked</Message></Error>";}
                } else if("DELETE".equals(exchange.getRequestMethod())) {
                    if("Trickle".equals(kind)||"SlowPreflight".equals(kind)||"ListPages".equals(kind)) {unexpected.incrementAndGet();code=500;body="<Error><Code>UnexpectedOperation</Code><Message>blocked</Message></Error>";}
                    else {deletes.incrementAndGet();code=204;body="";}
                } else {
                    unexpected.incrementAndGet();code=500;body="<Error><Code>UnexpectedOperation</Code><Message>blocked</Message></Error>";
                }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                if(json&&code==200)exchange.getResponseHeaders().add("Content-Type","application/json");
                else exchange.getResponseHeaders().add("Content-Type","application/xml");
                if(code==204) {exchange.sendResponseHeaders(204,-1);exchange.close();return;}
                exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            } catch(IOException ex) {
                writeFailures.incrementAndGet();
                try{exchange.close();}catch(Exception ignored){}
            }
        }
        String policyBody() {
            if("Public".equals(kind))return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\"}]}";
            if("PublicArray".equals(kind))return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]}}]}";
            if("PublicObjectString".equals(kind))return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"}}]}";
            if("PublicEscaped".equals(kind))return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"\\u002a\"}]}";
            if("PublicWhitespace".equals(kind))return "{\n  \"Version\": \"2012-10-17\",\n  \"Statement\": [ {\n    \"Effect\": \"Allow\",\n    \"Principal\": { \"AWS\" : [ \"*\" ] }\n  } ]\n}";
            if("PublicNotPrincipal".equals(kind))return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"NotPrincipal\":{\"AWS\":\"arn:aws:iam::123:user/demo\"}}]}";
            if("PublicUnparseable".equals(kind))return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\"}";
            if("PublicUnknownEffect".equals(kind))return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Sideways\",\"Principal\":\"*\"}]}";
            if("PrivateDeny".equals(kind))return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::123:user/demo\"}},{\"Effect\":\"Deny\",\"Principal\":\"*\"}]}";
            return null;
        }
        static Map<String,String> query(URI uri) {
            Map<String,String> map=new LinkedHashMap<>();
            String raw=uri.getRawQuery();
            if(raw==null)return map;
            for(String pair:raw.split("&")) {
                if(pair.isEmpty())continue;
                int eq=pair.indexOf('=');
                try {
                    if(eq<0)map.put(URLDecoder.decode(pair,"UTF-8"),"");
                    else map.put(URLDecoder.decode(pair.substring(0,eq),"UTF-8"),URLDecoder.decode(pair.substring(eq+1),"UTF-8"));
                } catch(Exception ex) {throw new IllegalArgumentException(ex);}
            }
            return map;
        }
    }
    private static final class Fixture {
        final String internalEndpoint,publicEndpoint,bucket,prefix,applicationAccessKey,applicationSecretKey;
        Fixture(String internalEndpoint,String publicEndpoint,String bucket,String prefix,String applicationAccessKey,String applicationSecretKey) {
            this.internalEndpoint=internalEndpoint;this.publicEndpoint=publicEndpoint;this.bucket=bucket;this.prefix=prefix;
            this.applicationAccessKey=applicationAccessKey;this.applicationSecretKey=applicationSecretKey;
        }
        static Fixture fromEnvironment() throws Exception {
            String manifest=System.getenv("COURSE_ATTACHMENTS_FIXTURE");if(manifest==null||manifest.trim().isEmpty())return null;
            String json=read(Paths.get(manifest));String credentialsFile=value(json,"credentialsFile");String credentials=read(Paths.get(credentialsFile));
            return new Fixture(value(json,"internalEndpoint"),value(json,"publicEndpoint"),value(json,"bucket"),value(json,"prefix"),
                value(credentials,"applicationAccessKey"),value(credentials,"applicationSecretKey"));
        }
        static String read(Path path) throws Exception {return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
        static String value(String json,String key) {
            Matcher m=Pattern.compile("\\\""+Pattern.quote(key)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(json);
            if(!m.find())throw new IllegalArgumentException("Missing fixture field: "+key);return unescape(m.group(1));
        }
        static String unescape(String value) {
            StringBuilder out=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c!='\\'){out.append(c);continue;}char e=value.charAt(++i);
                if(e=='u'){out.append((char)Integer.parseInt(value.substring(i+1,i+5),16));i+=4;}else if(e=='n')out.append('\n');else if(e=='r')out.append('\r');else if(e=='t')out.append('\t');else if(e=='b')out.append('\b');else if(e=='f')out.append('\f');else out.append(e);}
            return out.toString();
        }
    }
}
