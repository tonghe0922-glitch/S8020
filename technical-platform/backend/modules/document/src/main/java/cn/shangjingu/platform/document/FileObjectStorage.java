package cn.shangjingu.platform.document;

import java.time.Duration;

/** Binary object-store boundary. Database rows remain the source of file metadata truth. */
public interface FileObjectStorage {
    void put(String bucket, String objectKey, byte[] content, String contentType);

    StoredObject stat(String bucket, String objectKey);

    String presignGet(String bucket, String objectKey, Duration ttl);

    void remove(String bucket, String objectKey);

    record StoredObject(long sizeBytes, String contentType) {}
}
