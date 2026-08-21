package cn.shangjingu.platform.document;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.time.Duration;

/** Real MinIO/S3-compatible adapter used by the document platform kernel. */
public final class MinioFileObjectStorage implements FileObjectStorage {
    private final MinioClient client;

    public MinioFileObjectStorage(MinioClient client) {
        if (client == null) throw new IllegalArgumentException("minio client is required");
        this.client = client;
    }

    @Override
    public void put(String bucket, String objectKey, byte[] content, String contentType) {
        require(bucket, "bucket");
        require(objectKey, "objectKey");
        require(contentType, "contentType");
        if (content == null) throw new IllegalArgumentException("content is required");
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(input, content.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception failure) {
            throw new IllegalStateException("MinIO put failed", failure);
        }
    }

    @Override
    public StoredObject stat(String bucket, String objectKey) {
        require(bucket, "bucket");
        require(objectKey, "objectKey");
        try {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            return new StoredObject(response.size(), response.contentType());
        } catch (Exception failure) {
            throw new IllegalStateException("MinIO stat failed", failure);
        }
    }

    @Override
    public String presignGet(String bucket, String objectKey, Duration ttl) {
        require(bucket, "bucket");
        require(objectKey, "objectKey");
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("presigned download ttl must be between 1 second and 15 minutes");
        }
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(Math.toIntExact(ttl.toSeconds()))
                    .build());
        } catch (Exception failure) {
            throw new IllegalStateException("MinIO presign failed", failure);
        }
    }

    @Override
    public void remove(String bucket, String objectKey) {
        require(bucket, "bucket");
        require(objectKey, "objectKey");
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception failure) {
            throw new IllegalStateException("MinIO remove failed", failure);
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
