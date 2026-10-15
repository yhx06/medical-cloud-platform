package com.medcloud.course.paas.attachment;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import com.medcloud.course.paas.sdk.internal.minio.*;
import com.medcloud.course.paas.sdk.internal.minio.errors.ErrorResponseException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
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
    @Test void versionedOrLockedBucketRejected() throws Exception {
        for(String kind:new String[]{"Enabled","Suspended","Locked","Public","PublicArray"}) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger writes=new AtomicInteger();
            server.createContext("/",exchange->{
                if("DELETE".equals(exchange.getRequestMethod()))writes.incrementAndGet();
                String q=exchange.getRequestURI().getQuery();String xml="";int code=200;
                if("versioning".equals(q))xml="<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"+(kind.equals("Enabled")||kind.equals("Suspended")?"<Status>"+kind+"</Status>":"")+"</VersioningConfiguration>";
                else if("object-lock".equals(q)) {if(kind.equals("Locked"))xml="<ObjectLockConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><ObjectLockEnabled>Enabled</ObjectLockEnabled></ObjectLockConfiguration>";else{code=404;xml="<Error><Code>ObjectLockConfigurationNotFoundError</Code><Message>none</Message></Error>";}}
                else if("policy".equals(q)){if(kind.equals("Public"))xml="{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\"}]}";else if(kind.equals("PublicArray"))xml="{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]}}]}";else{code=404;xml="<Error><Code>NoSuchBucketPolicy</Code><Message>none</Message></Error>";}}
                else {code=500;xml="<Error><Code>UnexpectedOperation</Code><Message>blocked</Message></Error>";}
                byte[] body=xml.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","policy".equals(q)&&code==200?"application/json":"application/xml");exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);exchange.close();
            });server.start();
            try(MinioPrivateObjectStore store=store(server,3)) {assertEquals(503,assertThrows(AttachmentException.class,()->store.delete("course-attachments/abc")).getStatus());assertEquals(0,writes.get(),"preflight must reject before a storage mutation");}
            finally{server.stop(0);}
        }
    }
    @Test void storeTotalDeadlineBoundsRetries() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger requests=new AtomicInteger();
        server.createContext("/",exchange->{requests.incrementAndGet();try{Thread.sleep(5000);}catch(InterruptedException ignored){}finally{exchange.close();}});server.start();
        try(MinioPrivateObjectStore store=store(server,1)) {
            long start=System.nanoTime();assertEquals(503,assertThrows(AttachmentException.class,()->store.head("course-attachments/abc")).getStatus());assertTrue((System.nanoTime()-start)/1000000<2500);assertTrue(requests.get()>=1);
        }finally{server.stop(0);}
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
            assertEquals(issuedAt.plusSeconds(300),download.getExpiresAt());
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
