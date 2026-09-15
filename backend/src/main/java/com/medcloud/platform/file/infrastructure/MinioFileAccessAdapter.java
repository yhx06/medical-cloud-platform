package com.medcloud.platform.file.infrastructure;

import com.medcloud.platform.file.application.FileAccessPort;
import io.minio.BucketExistsArgs;
import io.minio.DeleteBucketPolicyArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class MinioFileAccessAdapter implements FileAccessPort {
    private static final long PART_SIZE = 10L * 1024 * 1024;
    private static final Map<String, String> MEDIA_TYPE_BY_SUFFIX = Map.of(
            ".pdf", "application/pdf",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".png", "image/png");

    private final MinioClient client;
    private final String bucket;

    @Autowired
    public MinioFileAccessAdapter(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey,
            @Value("${app.storage.bucket}") String bucket
    ) {
        this(MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build(), bucket);
    }

    MinioFileAccessAdapter(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public String store(
            UUID actorId,
            UUID tenantId,
            UUID businessObjectId,
            String fileName,
            InputStream content
    ) {
        String suffix = suffix(fileName);
        String mediaType = MEDIA_TYPE_BY_SUFFIX.get(suffix);
        if (mediaType == null) {
            throw new IllegalArgumentException("unsupported object suffix");
        }
        String objectKey = tenantId + "/" + businessObjectId + "/" + UUID.randomUUID() + suffix;
        try {
            ensurePrivateBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType(mediaType)
                    .stream(content, -1, PART_SIZE)
                    .build());
            return objectKey;
        } catch (Exception exception) {
            throw storageFailure(exception);
        }
    }

    @Override
    public InputStream load(
            UUID actorId,
            UUID tenantId,
            UUID businessObjectId,
            String objectKey
    ) {
        requireScopedObjectKey(tenantId, businessObjectId, objectKey);
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw storageFailure(exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw storageFailure(exception);
        }
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(Math.toIntExact(ttl.toSeconds()))
                    .build());
        } catch (Exception exception) {
            throw storageFailure(exception);
        }
    }

    private void ensurePrivateBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        client.deleteBucketPolicy(DeleteBucketPolicyArgs.builder().bucket(bucket).build());
    }

    private static String suffix(String fileName) {
        int suffixStart = fileName == null ? -1 : fileName.lastIndexOf('.');
        return suffixStart < 0 ? "" : fileName.substring(suffixStart).toLowerCase(Locale.ROOT);
    }

    private static void requireScopedObjectKey(UUID tenantId, UUID businessObjectId, String objectKey) {
        String requiredPrefix = tenantId + "/" + businessObjectId + "/";
        if (objectKey == null || !objectKey.startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("object key is outside the requested medical record scope");
        }
    }

    private static IllegalStateException storageFailure(Exception exception) {
        return new IllegalStateException("private object storage operation failed", exception);
    }
}
