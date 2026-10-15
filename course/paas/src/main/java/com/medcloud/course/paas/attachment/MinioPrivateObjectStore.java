package com.medcloud.course.paas.attachment;

import com.medcloud.course.paas.sdk.internal.minio.*;
import com.medcloud.course.paas.sdk.internal.minio.errors.ErrorResponseException;
import com.medcloud.course.paas.sdk.internal.minio.http.Method;
import com.medcloud.course.paas.sdk.internal.minio.messages.Item;
import com.medcloud.course.paas.sdk.internal.minio.messages.VersioningConfiguration;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Private, prefix-confined MinIO adapter. It never falls back to deployment root credentials. */
public final class MinioPrivateObjectStore implements PrivateObjectStore, AutoCloseable {
    private final MinioClient internalClient;
    private final MinioClient publicClient;
    private final String bucket;
    private final String prefix;
    private final int deadlineSeconds;
    private final ExecutorService calls;
    private final Object preflightLock=new Object();
    private volatile boolean preflightComplete;
    private volatile boolean closed;

    public MinioPrivateObjectStore(String internalEndpoint,String publicEndpoint,String region,String bucket,
                                   String accessKey,String secretKey,String prefix,AttachmentLimits limits) {
        if(internalEndpoint==null||publicEndpoint==null||region==null||bucket==null||accessKey==null||secretKey==null||prefix==null||limits==null)
            throw unavailable();
        this.bucket=bucket;this.prefix=prefix;this.deadlineSeconds=limits.getStoreDeadlineSeconds();
        this.internalClient=client(internalEndpoint,region,accessKey,secretKey,limits);
        this.publicClient=client(publicEndpoint,region,accessKey,secretKey,limits);
        this.calls=Executors.newFixedThreadPool(limits.getContainerConcurrency(),r->{Thread t=new Thread(r,"attachment-object-store");t.setDaemon(true);return t;});
    }

    private static MinioClient client(String endpoint,String region,String accessKey,String secretKey,AttachmentLimits limits) {
        MinioClient client=MinioClient.builder().endpoint(endpoint).region(region).credentials(accessKey,secretKey).build();
        long connect=Math.min(limits.getConnectTimeoutSeconds(),limits.getStoreDeadlineSeconds())*1000L;
        long total=limits.getStoreDeadlineSeconds()*1000L;
        client.setTimeout(connect,total,total);
        return client;
    }

    @Override public void put(String objectKey,ValidatedAttachment content) {
        requireKey(objectKey);
        if(content==null||content.getPath()==null||content.getSha256()==null)throw invalid();
        execute(()->{
            preflight();
            if(!Files.isRegularFile(content.getPath())||Files.size(content.getPath())!=content.getSizeBytes())throw invalid();
            Map<String,String> metadata=Collections.singletonMap("sha256",content.getSha256());
            try(InputStream input=Files.newInputStream(content.getPath())) {
                internalClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .stream(input,content.getSizeBytes(),-1).contentType(content.getContentType()).userMetadata(metadata).build());
            }
            return null;
        });
    }

    @Override public StoreObjectInfo head(String objectKey) {
        requireKey(objectKey);
        return execute(()->{
            preflight();
            try {
                StatObjectResponse response=internalClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
                String sha=metadata(response.userMetadata(),"sha256");
                if(sha==null||!sha.matches("[0-9a-f]{64}"))throw unavailable();
                return new StoreObjectInfo(response.size(),sha);
            } catch(ErrorResponseException ex) {if(isCode(ex,"NoSuchKey","NoSuchObject","NotFound"))return null;throw ex;}
        });
    }

    @Override public StoreDownload signGet(AttachmentSnapshot file,Instant issuedAt,int ttlSeconds) {
        if(file==null||issuedAt==null||ttlSeconds<1||ttlSeconds>604800)throw invalid();
        requireKey(file.getObjectKey());
        return execute(()->{
            preflight();
            String url=publicClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                .bucket(bucket).object(file.getObjectKey()).expiry(ttlSeconds).build());
            return new StoreDownload(url,issuedAt.plusSeconds(ttlSeconds));
        });
    }

    @Override public void delete(String objectKey) {
        requireKey(objectKey);
        execute(()->{preflight();internalClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());return null;});
    }

    @Override public List<String> listKeys(String requestedPrefix,String afterKey,int limit) {
        requireListPrefix(requestedPrefix);
        if(afterKey!=null)requireKey(afterKey);
        if(limit<1||limit>100)throw invalid();
        return execute(()->{
            preflight();
            List<String> keys=new ArrayList<>();
            Iterable<Result<Item>> results=internalClient.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(requestedPrefix)
                .startAfter(afterKey).recursive(true).maxKeys(limit).build());
            for(Result<Item> result:results) {
                String key=result.get().objectName();
                if(key!=null&&key.startsWith(prefix)&&keys.size()<limit)keys.add(key);
            }
            Collections.sort(keys);return keys;
        });
    }

    private void preflight() throws Exception {
        if(preflightComplete)return;
        synchronized(preflightLock) {
            if(preflightComplete)return;
            VersioningConfiguration versioning=internalClient.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build());
            if(versioning!=null&&versioning.status()!=null&&versioning.status()!=VersioningConfiguration.Status.OFF)throw unavailable();
            try {
                if(internalClient.getObjectLockConfiguration(GetObjectLockConfigurationArgs.builder().bucket(bucket).build())!=null)throw unavailable();
            } catch(ErrorResponseException ex) {
                if(!isCode(ex,"ObjectLockConfigurationNotFoundError","NoSuchObjectLockConfiguration","NotFound"))throw ex;
            }
            try {
                String policy=internalClient.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build());
                if(grantsAnonymousAccess(policy))throw unavailable();
            } catch(ErrorResponseException ex) {
                if(!isCode(ex,"NoSuchBucketPolicy","NoSuchPolicy","NotFound"))throw ex;
            }
            preflightComplete=true;
        }
    }

    private static boolean grantsAnonymousAccess(String policy) {
        if(policy==null||policy.trim().isEmpty())return false;
        String compact=policy.replaceAll("\\s+","");
        return compact.contains("\"Effect\":\"Allow\"")&&
            (compact.contains("\"Principal\":\"*\"")||compact.contains("\"AWS\":\"*\""));
    }

    private <T> T execute(Callable<T> operation) {
        if(closed)throw unavailable();
        Future<T> future;
        try {future=calls.submit(operation);} catch(RejectedExecutionException ex){throw unavailable();}
        try {return future.get(deadlineSeconds,TimeUnit.SECONDS);}
        catch(TimeoutException ex){future.cancel(true);throw unavailable();}
        catch(InterruptedException ex){future.cancel(true);Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException ex){
            Throwable cause=ex.getCause();if(cause instanceof AttachmentException)throw (AttachmentException)cause;throw unavailable();
        }
    }

    private void requireKey(String key) {
        if(key==null||key.length()>128||!key.startsWith(prefix)||key.equals(prefix)||key.endsWith("/")||key.contains("..")||key.contains("//")||
            !key.matches("[A-Za-z0-9_./-]+"))throw invalid();
    }
    private void requireListPrefix(String value) {
        if(value==null||value.length()>128||!value.startsWith(prefix)||value.contains("..")||value.contains("//")||
            !value.matches("[A-Za-z0-9_./-]+"))throw invalid();
    }
    private static String metadata(Map<String,String> values,String wanted) {
        if(values==null)return null;for(Map.Entry<String,String> entry:values.entrySet())if(wanted.equalsIgnoreCase(entry.getKey()))return entry.getValue();return null;
    }
    private static boolean isCode(ErrorResponseException ex,String... codes) {
        String actual=ex.errorResponse()==null?null:ex.errorResponse().code();
        for(String code:codes)if(code.equals(actual))return true;return false;
    }
    private static AttachmentException invalid(){return new AttachmentException(400,"FILE_INVALID");}
    private static AttachmentException unavailable(){return new AttachmentException(503,"OBJECT_STORE_UNAVAILABLE");}

    @Override public void close() {
        closed=true;calls.shutdownNow();
        try{internalClient.close();}catch(Exception ignored){}
        try{publicClient.close();}catch(Exception ignored){}
    }
}
