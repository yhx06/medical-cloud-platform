package com.medcloud.course.paas.attachment;

import java.net.URI;
import java.nio.file.*;
import java.util.Map;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Produces;

/** Attachment configuration fails closed without failing core application startup. */
@ApplicationScoped
public class AttachmentConfiguration {
    private boolean available;
    private AttachmentLimits limits = new AttachmentLimits();
    private Path tempDirectory;
    private String internalEndpoint, publicEndpoint, region, bucket, accessKey, secretKey, prefix;
    private MinioPrivateObjectStore store;

    public AttachmentConfiguration() { configure(System.getenv()); }
    private AttachmentConfiguration(Map<String,String> environment) { configure(environment); }
    public static AttachmentConfiguration fromEnvironment(Map<String,String> environment) { return new AttachmentConfiguration(environment); }

    private void configure(Map<String,String> e) {
        try {
            limits=new AttachmentLimits(number(e,"MAX_BYTES",10485760),number(e,"MAX_PIXELS",20000000),
                integer(e,"MAX_PDF_PAGES",200),integer(e,"CONCURRENCY",4),integer(e,"UPLOADER_PENDING_LIMIT",2),3,90,120);
            tempDirectory=Paths.get(e.getOrDefault("COURSE_ATTACHMENTS_TEMP_DIR",System.getProperty("java.io.tmpdir")+"/medical-course-attachments")).toAbsolutePath().normalize();
            if (!"true".equals(e.get("COURSE_ATTACHMENTS_ENABLED"))) return;
            internalEndpoint=endpoint(required(e,"COURSE_S3_INTERNAL_ENDPOINT"));
            publicEndpoint=endpoint(required(e,"COURSE_S3_PUBLIC_ENDPOINT"));
            region=required(e,"COURSE_S3_REGION");bucket=required(e,"COURSE_S3_BUCKET");
            accessKey=required(e,"COURSE_S3_ACCESS_KEY");secretKey=required(e,"COURSE_S3_SECRET_KEY");
            prefix=e.getOrDefault("COURSE_S3_PREFIX","course-attachments/");
            if(!region.matches("[a-z0-9-]{1,64}") || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") ||
                !prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/") || prefix.length()>80) throw new IllegalArgumentException();
            // Explicit root variables, if supplied by a deployment, must never become app credentials.
            if(accessKey.equals(e.get("MINIO_ROOT_USER")) || accessKey.equals("minioadmin")) throw new IllegalArgumentException();
            available=true;
        } catch(RuntimeException ignored) { available=false; }
    }
    private static String required(Map<String,String> e,String key) {
        String value=e.get(key);if(value==null || value.isEmpty() || !value.equals(value.trim())) throw new IllegalArgumentException();return value;
    }
    private static long number(Map<String,String> e,String suffix,long fallback) {
        String value=e.get("COURSE_ATTACHMENTS_"+suffix);if(value==null)return fallback;
        if(!value.matches("[1-9][0-9]*"))throw new IllegalArgumentException();return Long.parseLong(value);
    }
    private static int integer(Map<String,String> e,String suffix,int fallback) {long n=number(e,suffix,fallback);if(n>Integer.MAX_VALUE)throw new IllegalArgumentException();return (int)n;}
    private static String endpoint(String value) {
        URI u=URI.create(value);String host=u.getHost();
        if(host==null || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null ||
            !(u.getPath().isEmpty() || "/".equals(u.getPath())) || u.getPort()>65535 || u.getPort()==0) throw new IllegalArgumentException();
        boolean local="localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"[::1]".equals(host)||"::1".equals(host);
        if(!"https".equals(u.getScheme()) && !("http".equals(u.getScheme()) && local))throw new IllegalArgumentException();
        return value;
    }
    public boolean isAvailable() { return available; }
    @Produces @Dependent
    public AttachmentLimits getLimits() { return limits; }
    public Path getTempDirectory() { if(tempDirectory==null)throw unavailable();return tempDirectory; }
    public synchronized PrivateObjectStore createStore() {
        if(!available)throw unavailable();
        if(store==null)store=new MinioPrivateObjectStore(internalEndpoint,publicEndpoint,region,bucket,accessKey,secretKey,prefix,limits);
        return store;
    }
    @PreDestroy public synchronized void close() { if(store!=null)store.close(); }
    static AttachmentException unavailable() { return new AttachmentException(503,"OBJECT_STORE_UNAVAILABLE"); }
}
