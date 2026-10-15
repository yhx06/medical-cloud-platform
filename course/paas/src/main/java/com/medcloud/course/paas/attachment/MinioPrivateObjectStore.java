package com.medcloud.course.paas.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medcloud.course.paas.sdk.internal.minio.*;
import com.medcloud.course.paas.sdk.internal.minio.errors.ErrorResponseException;
import com.medcloud.course.paas.sdk.internal.minio.http.Method;
import com.medcloud.course.paas.sdk.internal.minio.messages.Item;
import com.medcloud.course.paas.sdk.internal.minio.messages.VersioningConfiguration;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

/** Private, prefix-confined MinIO adapter. It never falls back to deployment root credentials. */
public final class MinioPrivateObjectStore implements PrivateObjectStore, AutoCloseable {
    private static final ObjectMapper POLICY_JSON=new ObjectMapper();
    private static final DateTimeFormatter SIGNATURE_MOMENT=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final MinioClient internalClient;
    private final MinioClient publicClient;
    private final String bucket;
    private final String prefix;
    private final int deadlineSeconds;
    private final ExecutorService calls;
    private final Object preflightLock=new Object();
    private volatile boolean preflightComplete;
    private volatile boolean closed;

    /** One storage attempt bound by a single absolute deadline covering queueing, preflight and retries. */
    private interface StoreOperation<T> { T run(long deadlineNanos) throws Exception; }

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
        long connect=Math.min(limits.getConnectTimeoutSeconds(),limits.getStoreDeadlineSeconds())*1000L;
        long total=limits.getStoreDeadlineSeconds()*1000L;
        // callTimeout is the total HTTP call deadline: slow drips and retries cannot outlive it.
        OkHttpClient http=new OkHttpClient().newBuilder()
            .connectTimeout(connect,TimeUnit.MILLISECONDS)
            .writeTimeout(total,TimeUnit.MILLISECONDS)
            .readTimeout(total,TimeUnit.MILLISECONDS)
            .callTimeout(total,TimeUnit.MILLISECONDS)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .build();
        return MinioClient.builder().endpoint(endpoint).region(region).credentials(accessKey,secretKey).httpClient(http,true).build();
    }

    @Override public void put(String objectKey,ValidatedAttachment content) {
        requireKey(objectKey);
        if(content==null||content.getPath()==null||content.getSha256()==null)throw invalid();
        execute(deadline->{
            preflight(deadline);
            checkDeadline(deadline);
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
        return execute(deadline->{
            preflight(deadline);
            checkDeadline(deadline);
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
        return execute(deadline->{
            preflight(deadline);
            checkDeadline(deadline);
            String url=publicClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                .bucket(bucket).object(file.getObjectKey()).expiry(ttlSeconds).build());
            // Design: expiresAt is the signature generation moment plus the TTL, not an earlier issuedAt.
            Instant signedAt=signatureMoment(url);
            return new StoreDownload(url,signedAt.plusSeconds(ttlSeconds));
        });
    }

    @Override public void delete(String objectKey) {
        requireKey(objectKey);
        execute(deadline->{preflight(deadline);checkDeadline(deadline);internalClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());return null;});
    }

    @Override public List<String> listKeys(String requestedPrefix,String afterKey,int limit) {
        requireListPrefix(requestedPrefix);
        if(afterKey!=null)requireKey(afterKey);
        if(limit<1||limit>100)throw invalid();
        return execute(deadline->{
            preflight(deadline);
            checkDeadline(deadline);
            List<String> keys=new ArrayList<>();
            Iterator<Result<Item>> results=internalClient.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(requestedPrefix)
                .startAfter(afterKey).recursive(true).maxKeys(limit).build()).iterator();
            // maxKeys is a page size, not a total cap: stop consuming as soon as the batch is full.
            while(keys.size()<limit&&results.hasNext()) {
                checkDeadline(deadline);
                String key=results.next().get().objectName();
                if(key!=null&&key.startsWith(prefix))keys.add(key);
            }
            Collections.sort(keys);return keys;
        });
    }

    private void preflight(long deadline) throws Exception {
        if(preflightComplete)return;
        synchronized(preflightLock) {
            if(preflightComplete)return;
            checkDeadline(deadline);
            VersioningConfiguration versioning=internalClient.getBucketVersioning(GetBucketVersioningArgs.builder().bucket(bucket).build());
            if(versioning!=null&&versioning.status()!=null&&versioning.status()!=VersioningConfiguration.Status.OFF)throw unavailable();
            try {
                checkDeadline(deadline);
                if(internalClient.getObjectLockConfiguration(GetObjectLockConfigurationArgs.builder().bucket(bucket).build())!=null)throw unavailable();
            } catch(ErrorResponseException ex) {
                if(!isCode(ex,"ObjectLockConfigurationNotFoundError","NoSuchObjectLockConfiguration","NotFound"))throw ex;
            }
            try {
                checkDeadline(deadline);
                String policy=internalClient.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build());
                if(grantsAnonymousAccess(policy))throw unavailable();
            } catch(ErrorResponseException ex) {
                if(!isCode(ex,"NoSuchBucketPolicy","NoSuchPolicy","NotFound"))throw ex;
            }
            preflightComplete=true;
        }
    }

    /**
     * Fail-closed structural scan of a bucket policy: any statement that allows an
     * anonymous principal, or any shape we cannot prove safe, blocks the operation.
     */
    static boolean grantsAnonymousAccess(String policy) {
        if(policy==null||policy.trim().isEmpty())return false;
        JsonNode root;
        try {root=POLICY_JSON.readTree(policy);} catch(Exception ex) {return true;}
        if(root==null||!root.isObject())return true;
        JsonNode statements=root.get("Statement");
        if(statements==null||statements.isNull())return true;
        if(statements.isObject())return statementGrantsAnonymous(statements);
        if(!statements.isArray())return true;
        for(JsonNode statement:statements)if(statementGrantsAnonymous(statement))return true;
        return false;
    }

    private static boolean statementGrantsAnonymous(JsonNode statement) {
        if(statement==null||!statement.isObject())return true;
        JsonNode effect=statement.get("Effect");
        if(effect==null||!effect.isTextual())return true;
        if("Deny".equals(effect.asText()))return false;
        if(!"Allow".equals(effect.asText()))return true;
        if(statement.has("NotPrincipal")&&!statement.get("NotPrincipal").isNull())return true;
        JsonNode principal=statement.get("Principal");
        if(principal==null||principal.isNull())return true;
        return principalIsAnonymous(principal);
    }

    private static boolean principalIsAnonymous(JsonNode principal) {
        if(principal.isTextual())return "*".equals(principal.asText());
        if(principal.isArray()) {for(JsonNode item:principal)if(principalIsAnonymous(item))return true;return false;}
        if(principal.isObject()) {
            if(principal.size()==0)return true;
            Iterator<Map.Entry<String,JsonNode>> fields=principal.fields();
            while(fields.hasNext())if(principalIsAnonymous(fields.next().getValue()))return true;
            return false;
        }
        return true;
    }

    /** Exact signature moment recorded in the presigned URL; unknown shapes are rejected. */
    private static Instant signatureMoment(String url) {
        if(url==null)throw unavailable();
        int at=url.indexOf("X-Amz-Date=");
        if(at<0)throw unavailable();
        String value=url.substring(at+"X-Amz-Date=".length());
        int end=value.indexOf('&');
        if(end>=0)value=value.substring(0,end);
        try {return LocalDateTime.parse(value,SIGNATURE_MOMENT).toInstant(ZoneOffset.UTC);}
        catch(Exception ex) {throw unavailable();}
    }

    private <T> T execute(StoreOperation<T> operation) {
        if(closed)throw unavailable();
        long deadlineNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(deadlineSeconds);
        Future<T> future;
        try {future=calls.submit(()->operation.run(deadlineNanos));} catch(RejectedExecutionException ex){throw unavailable();}
        try {return future.get(deadlineSeconds,TimeUnit.SECONDS);}
        catch(TimeoutException ex){future.cancel(true);throw unavailable();}
        catch(InterruptedException ex){future.cancel(true);Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException ex){
            Throwable cause=ex.getCause();if(cause instanceof AttachmentException)throw (AttachmentException)cause;throw unavailable();
        }
    }

    private static void checkDeadline(long deadlineNanos) {
        // A cancelled attempt must not start another storage call; clear the flag for the pooled thread.
        if(Thread.interrupted()||System.nanoTime()-deadlineNanos>=0)throw unavailable();
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
