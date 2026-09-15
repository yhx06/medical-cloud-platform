package com.medcloud.platform.file.infrastructure;

import io.minio.BucketExistsArgs;
import io.minio.DeleteBucketPolicyArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioFileAccessAdapterTest {
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECORD_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID DOCTOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void storeCreatesThePrivateBucketAndUsesAnOpaqueTenantRecordKey() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        MinioFileAccessAdapter adapter = new MinioFileAccessAdapter(client, "medical-private");

        String objectKey = adapter.store(
                DOCTOR_ID,
                TENANT_ID,
                RECORD_ID,
                "../../patient-name.pdf",
                new ByteArrayInputStream("%PDF".getBytes()));

        ArgumentCaptor<MakeBucketArgs> makeBucket = ArgumentCaptor.forClass(MakeBucketArgs.class);
        verify(client).makeBucket(makeBucket.capture());
        assertThat(makeBucket.getValue().bucket()).isEqualTo("medical-private");

        ArgumentCaptor<DeleteBucketPolicyArgs> deletePolicy =
                ArgumentCaptor.forClass(DeleteBucketPolicyArgs.class);
        verify(client).deleteBucketPolicy(deletePolicy.capture());
        assertThat(deletePolicy.getValue().bucket()).isEqualTo("medical-private");

        ArgumentCaptor<PutObjectArgs> putObject = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(putObject.capture());
        assertThat(putObject.getValue().bucket()).isEqualTo("medical-private");
        assertThat(putObject.getValue().object()).isEqualTo(objectKey);
        assertThat(putObject.getValue().contentType()).isEqualTo("application/pdf");
        assertThat(objectKey)
                .startsWith(TENANT_ID + "/" + RECORD_ID + "/")
                .endsWith(".pdf")
                .doesNotContain("patient-name")
                .doesNotContain("..");
        String opaquePart = objectKey.substring((TENANT_ID + "/" + RECORD_ID + "/").length());
        assertThat(UUID.fromString(opaquePart.substring(0, opaquePart.length() - 4))).isNotNull();
    }

    @Test
    void deleteUsesTheConfiguredPrivateBucketAndExactObjectKey() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioFileAccessAdapter adapter = new MinioFileAccessAdapter(client, "medical-private");
        String objectKey = TENANT_ID + "/" + RECORD_ID + "/opaque.pdf";

        adapter.delete(objectKey);

        ArgumentCaptor<RemoveObjectArgs> remove = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client).removeObject(remove.capture());
        assertThat(remove.getValue().bucket()).isEqualTo("medical-private");
        assertThat(remove.getValue().object()).isEqualTo(objectKey);
    }

    @Test
    void presignUsesGetAndTheRequestedFiveMinuteExpiry() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://objects.example/signed");
        MinioFileAccessAdapter adapter = new MinioFileAccessAdapter(client, "medical-private");
        String objectKey = TENANT_ID + "/" + RECORD_ID + "/opaque.pdf";

        String url = adapter.presignGet(objectKey, Duration.ofMinutes(5));

        ArgumentCaptor<GetPresignedObjectUrlArgs> presign =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(client).getPresignedObjectUrl(presign.capture());
        assertThat(url).isEqualTo("https://objects.example/signed");
        assertThat(presign.getValue().bucket()).isEqualTo("medical-private");
        assertThat(presign.getValue().object()).isEqualTo(objectKey);
        assertThat(presign.getValue().method()).isEqualTo(Method.GET);
        assertThat(presign.getValue().expiry()).isEqualTo(300);
    }
}
