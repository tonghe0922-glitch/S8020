package cn.shangjingu.platform.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class MinioFileObjectStorageIT {
    private static final String IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z-cpuv1";
    private static final String ACCESS_KEY = "phase06minio";
    private static final String SECRET_KEY = "phase06-minio-secret";
    private static GenericContainer<?> minio;
    private static MinioClient client;
    private static MinioFileObjectStorage storage;

    @BeforeAll
    static void startMinio() throws Exception {
        minio = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data", "--address", ":9000")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        minio.start();
        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        client = MinioClient.builder().endpoint(endpoint).credentials(ACCESS_KEY, SECRET_KEY).build();
        storage = new MinioFileObjectStorage(client);
    }

    @AfterAll
    static void stopMinio() {
        if (minio != null) minio.stop();
    }

    @Test
    void putStatPresignAndRemoveUseRealMinio() throws Exception {
        String bucket = "phase06-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        String objectKey = "tenant/file-" + UUID.randomUUID();
        byte[] content = "phase06-real-minio".getBytes(StandardCharsets.UTF_8);

        storage.put(bucket, objectKey, content, "text/plain");
        FileObjectStorage.StoredObject stat = storage.stat(bucket, objectKey);
        assertEquals(content.length, stat.sizeBytes());
        assertEquals("text/plain", stat.contentType());

        String url = storage.presignGet(bucket, objectKey, Duration.ofSeconds(30));
        assertTrue(url.contains(objectKey));
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(content, response.body());

        storage.remove(bucket, objectKey);
        assertThrows(IllegalStateException.class, () -> storage.stat(bucket, objectKey));
    }
}
