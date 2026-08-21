package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.document.FileDownloadGuard;
import cn.shangjingu.platform.document.FileObjectService;
import cn.shangjingu.platform.document.FileObjectStorage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase06FileObjectDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000611");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000612");
    private static final String API_PASSWORD = "p06_file_api_" + shortId();
    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;
    private static JdbcTemplate apiJdbc;
    private static TenantTransactionRunner transactions;
    private static RecordingStorage storage;
    private static AtomicInteger guardCalls;
    private static FileObjectService service;

    @BeforeAll
    static void installApprovedBaseline() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase06-file-bootstrap-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE sjg_api_runtime PASSWORD '" + API_PASSWORD + "'");
            statement.execute("CREATE DATABASE sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");
        execute("insert into core.tenant(id,tenant_code,tenant_name,status,timezone) values ('" + TENANT_B
                + "','PHASE06_FILE_B','PHASE-06 File Tenant B','ACTIVE','Asia/Shanghai')");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_api_runtime", API_PASSWORD);
        apiJdbc = new JdbcTemplate(dataSource);
        transactions = new TenantTransactionRunner(apiJdbc, new DataSourceTransactionManager(dataSource));
        storage = new RecordingStorage();
        guardCalls = new AtomicInteger();
        FileDownloadGuard guard = file -> guardCalls.incrementAndGet();
        service = new FileObjectService(apiJdbc, storage, guard);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void fileMetadataOverlayRlsSafeBindingAndSignedDownloadAreEnforced() throws Exception {
        byte[] bytes = "phase06-file-evidence".getBytes(StandardCharsets.UTF_8);
        UUID fileId = transactions.required(TENANT_A, () -> service.upload(new FileObjectService.UploadCommand(
                TENANT_A, null, "evidence.txt", "text/plain", "phase06-files", bytes, "P1_INTERNAL", 2)));

        assertEquals("P1_INTERNAL", scalarString("select sensitive_level from document.file_object where id='" + fileId + "'"));
        assertEquals(2L, scalarLong("select version_no from document.file_object where id='" + fileId + "'"));
        assertEquals("PENDING", scalarString("select virus_scan_status from document.file_object where id='" + fileId + "'"));
        assertEquals(FileObjectService.sha256(bytes), scalarString("select sha256 from document.file_object where id='" + fileId + "'"));

        UUID businessId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> transactions.required(TENANT_A, () -> service.bindAttachment(
                attachment(fileId, businessId))));
        assertThrows(IllegalStateException.class, () -> transactions.required(TENANT_A,
                () -> service.presignDownload(TENANT_A, fileId, Duration.ofSeconds(30))));

        transactions.required(TENANT_A, () -> { service.transitionScan(TENANT_A, null, fileId, FileObjectService.ScanStatus.SCANNING); return null; });
        transactions.required(TENANT_A, () -> { service.transitionScan(TENANT_A, null, fileId, FileObjectService.ScanStatus.SAFE); return null; });
        UUID linkId = transactions.required(TENANT_A, () -> service.bindAttachment(attachment(fileId, businessId)));
        assertEquals(1L, scalarLong("select count(*) from document.attachment_link where id='" + linkId + "' and is_evidence"));

        String signed = transactions.required(TENANT_A, () -> service.presignDownload(TENANT_A, fileId, Duration.ofSeconds(30)));
        assertTrue(signed.startsWith("https://signed.invalid/"));
        assertEquals(1, guardCalls.get(), "download must cross explicit authorization/Step-Up guard boundary");

        long otherTenantVisibility = transactions.required(TENANT_B, () -> apiJdbc.queryForObject(
                "select count(*) from document.file_object where id=?", Long.class, fileId));
        assertEquals(0L, otherTenantVisibility, "RLS must hide another tenant's file metadata");
    }

    @Test
    void infectedFileCannotBindOrDownload() {
        UUID fileId = transactions.required(TENANT_A, () -> service.upload(new FileObjectService.UploadCommand(
                TENANT_A, null, "infected.bin", "application/octet-stream", "phase06-files",
                new byte[]{1,2,3}, "P1_INTERNAL", 1)));
        transactions.required(TENANT_A, () -> { service.transitionScan(TENANT_A, null, fileId, FileObjectService.ScanStatus.SCANNING); return null; });
        transactions.required(TENANT_A, () -> { service.transitionScan(TENANT_A, null, fileId, FileObjectService.ScanStatus.INFECTED); return null; });
        assertThrows(IllegalStateException.class, () -> transactions.required(TENANT_A,
                () -> service.bindAttachment(attachment(fileId, UUID.randomUUID()))));
        assertThrows(IllegalStateException.class, () -> transactions.required(TENANT_A,
                () -> service.presignDownload(TENANT_A, fileId, Duration.ofSeconds(30))));
    }

    private static FileObjectService.AttachmentCommand attachment(UUID fileId, UUID businessId) {
        return new FileObjectService.AttachmentCommand(TENANT_A, null, "PHASE06_TEST", businessId,
                "evidence", fileId, "EVIDENCE", 0, true);
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway-overlays").resolve(overlayFolder));
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT_A.toString(),
                        "sjg_tenant_code", "PHASE06_FILE_A",
                        "sjg_tenant_name", "PHASE-06 File Tenant A"))
                .cleanDisabled(true).load();
        assertTrue(flyway.migrate().success);
        flyway.validate();
    }

    private static Connection admin(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
    }

    private static String jdbcUrl(String database) {
        String url = postgres.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query >= 0 ? url.substring(query) : "";
        String base = query >= 0 ? url.substring(0, query) : url;
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("AGENT.md")) && Files.isDirectory(current.resolve("Knowledge Base"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static final class RecordingStorage implements FileObjectStorage {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();
        private final Map<String, String> contentTypes = new LinkedHashMap<>();
        private String key(String bucket, String objectKey) { return bucket + "/" + objectKey; }
        @Override public void put(String bucket, String objectKey, byte[] content, String contentType) {
            objects.put(key(bucket, objectKey), content.clone()); contentTypes.put(key(bucket, objectKey), contentType);
        }
        @Override public StoredObject stat(String bucket, String objectKey) {
            byte[] value = objects.get(key(bucket, objectKey));
            if (value == null) throw new IllegalStateException("missing test object");
            return new StoredObject(value.length, contentTypes.get(key(bucket, objectKey)));
        }
        @Override public String presignGet(String bucket, String objectKey, Duration ttl) {
            return "https://signed.invalid/" + objectKey + "?ttl=" + ttl.toSeconds();
        }
        @Override public void remove(String bucket, String objectKey) {
            objects.remove(key(bucket, objectKey)); contentTypes.remove(key(bucket, objectKey));
        }
    }
}
