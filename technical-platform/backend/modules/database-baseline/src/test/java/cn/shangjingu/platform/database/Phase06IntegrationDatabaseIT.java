package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.integration.IntegrationConflictException;
import cn.shangjingu.platform.integration.IntegrationHttpClient;
import cn.shangjingu.platform.integration.WebhookIngressService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase06IntegrationDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000631");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000632");
    private static final String API_PASSWORD = "p06_integration_api_" + shortId();
    private static final String WORKER_PASSWORD = "p06_integration_worker_" + shortId();
    private static PostgreSQLContainer<?> postgres;
    private static HttpServer provider;
    private static AtomicInteger providerCalls;
    private static AtomicReference<String> lastIdempotencyKey;
    private static Path repoRoot;
    private static JdbcTemplate apiJdbc;
    private static TenantTransactionRunner apiTransactions;
    private static IntegrationHttpClient http;
    private static WebhookIngressService webhooks;

    @BeforeAll
    static void installBaseline() throws Exception {
        providerCalls = new AtomicInteger();
        lastIdempotencyKey = new AtomicReference<>();
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/provider", exchange -> {
            providerCalls.incrementAndGet();
            lastIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            boolean fail = new String(body, StandardCharsets.UTF_8).contains("fail");
            if (!fail) exchange.getResponseHeaders().add("X-Provider-Reference", "provider-ref-631");
            byte[] response = (fail ? "failed" : "accepted").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 503 : 202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();

        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase06-integration-bootstrap-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres");
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE sjg_api_runtime PASSWORD '" + API_PASSWORD + "'");
            statement.execute("ALTER ROLE sjg_worker_runtime PASSWORD '" + WORKER_PASSWORD + "'");
            statement.execute("CREATE DATABASE sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");
        execute("insert into core.tenant(id,tenant_code,tenant_name,status,timezone) values ('" + TENANT_B
                + "','PHASE06_INT_B','PHASE-06 Integration Tenant B','ACTIVE','Asia/Shanghai')");
        String uri = "http://127.0.0.1:" + provider.getAddress().getPort() + "/provider";
        execute(
                "insert into integration.endpoint(id,tenant_id,endpoint_code,system_name,protocol,endpoint_uri,auth_type,timeout_ms,retry_policy,circuit_policy,enabled) values ('"
                        + UUID.randomUUID() + "','" + TENANT_A + "','PHASE06_HTTP','Phase06 Local Provider','HTTP','"
                        + uri + "','NONE',3000,'{\"max_attempts\":3}'::jsonb,'{\"mode\":\"external\"}'::jsonb,true)");
        execute(
                "insert into integration.endpoint(id,tenant_id,endpoint_code,system_name,protocol,endpoint_uri,auth_type,timeout_ms,enabled) values ('"
                        + UUID.randomUUID() + "','" + TENANT_A
                        + "','PHASE06_WEBHOOK','Phase06 Webhook Provider','HTTPS','https://webhook.invalid','HMAC',3000,true)");

        DriverManagerDataSource apiData =
                new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_api_runtime", API_PASSWORD);
        DriverManagerDataSource workerData =
                new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_worker_runtime", WORKER_PASSWORD);
        apiJdbc = new JdbcTemplate(apiData);
        JdbcTemplate workerJdbc = new JdbcTemplate(workerData);
        apiTransactions = new TenantTransactionRunner(apiJdbc, new DataSourceTransactionManager(apiData));
        http = new IntegrationHttpClient(
                workerJdbc, new DataSourceTransactionManager(workerData), HttpClient.newHttpClient(), List.of());
        webhooks = new WebhookIngressService(apiJdbc, new TransactionalOutboxService(apiJdbc));
    }

    @AfterAll
    static void stopAll() {
        if (provider != null) provider.stop(0);
        if (postgres != null) postgres.stop();
    }

    @Test
    void outboundHttpUsesStableBusinessIdempotencyAndDurableRequestEvidence() throws Exception {
        String business = "notify:" + shortId();
        IntegrationHttpClient.CallResult first = http.call(
                TENANT_A,
                new IntegrationHttpClient.CallCommand("PHASE06_HTTP", "req-" + shortId(), business, "{\"value\":1}"));
        assertTrue(first.success());
        assertFalse(first.replayed());
        assertEquals("202", first.responseCode());
        assertEquals("provider-ref-631", first.providerReference());
        assertEquals(1, providerCalls.get());
        assertEquals(business, lastIdempotencyKey.get());
        assertEquals(
                1L,
                scalarLong("select count(*) from integration.request_log where tenant_id='" + TENANT_A
                        + "' and business_key='" + business + "' and success"));
        assertEquals(
                "provider-ref-631",
                scalarString("select provider_reference from integration.request_log where tenant_id='" + TENANT_A
                        + "' and business_key='" + business + "' and success"));

        IntegrationHttpClient.CallResult replay = http.call(
                TENANT_A,
                new IntegrationHttpClient.CallCommand("PHASE06_HTTP", "req-" + shortId(), business, "{\"value\":1}"));
        assertTrue(replay.success());
        assertTrue(replay.replayed());
        assertEquals(1, providerCalls.get(), "successful business idempotency must suppress duplicate provider call");
        assertThrows(
                IntegrationConflictException.class,
                () -> http.call(
                        TENANT_A,
                        new IntegrationHttpClient.CallCommand(
                                "PHASE06_HTTP", "req-" + shortId(), business, "{\"value\":2}")));

        String failedBusiness = "fail:" + shortId();
        IntegrationHttpClient.CallResult failed = http.call(
                TENANT_A,
                new IntegrationHttpClient.CallCommand(
                        "PHASE06_HTTP", "req-" + shortId(), failedBusiness, "{\"fail\":true}"));
        assertFalse(failed.success());
        assertEquals("503", failed.responseCode());
        IntegrationHttpClient.CallResult retry = http.call(
                TENANT_A,
                new IntegrationHttpClient.CallCommand(
                        "PHASE06_HTTP", "req-" + shortId(), failedBusiness, "{\"fail\":true}"));
        assertFalse(retry.success());
        assertEquals(
                3, providerCalls.get(), "failed attempts may retry while keeping one stable provider idempotency key");
        assertEquals(
                2L,
                scalarLong("select count(*) from integration.request_log where business_key='" + failedBusiness
                        + "' and not success"));

        DriverManagerDataSource tenantBData =
                new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_worker_runtime", WORKER_PASSWORD);
        JdbcTemplate tenantBJdbc = new JdbcTemplate(tenantBData);
        TenantTransactionRunner tenantBRunner =
                new TenantTransactionRunner(tenantBJdbc, new DataSourceTransactionManager(tenantBData));
        long tenantBVisible = tenantBRunner.required(
                TENANT_B,
                () -> tenantBJdbc.queryForObject(
                        "select count(*) from integration.request_log where tenant_id=?", Long.class, TENANT_A));
        assertEquals(0L, tenantBVisible, "integration request evidence must obey RLS");
    }

    @Test
    void webhookProviderEventIsContentSafeDeduplicatedAndSignatureGated() throws Exception {
        String providerEvent = "evt-" + shortId();
        WebhookIngressService.ReceiveCommand valid = new WebhookIngressService.ReceiveCommand(
                "PHASE06_WEBHOOK", providerEvent, "DELIVERY_RECEIPT", "{\"status\":\"delivered\"}", "good");
        WebhookIngressService.WebhookResult first = apiTransactions.required(
                TENANT_A, () -> webhooks.receive(TENANT_A, null, valid, request -> "good".equals(request.signature())));
        assertFalse(first.duplicate());
        assertTrue(first.signatureValid());
        assertEquals("RECEIVED", first.status());
        WebhookIngressService.WebhookResult duplicate = apiTransactions.required(
                TENANT_A, () -> webhooks.receive(TENANT_A, null, valid, request -> "good".equals(request.signature())));
        assertEquals(first.id(), duplicate.id());
        assertTrue(duplicate.duplicate());
        assertEquals(
                1L,
                scalarLong("select count(*) from integration.webhook_event where provider_event_id='" + providerEvent
                        + "'"));
        assertEquals(
                1L,
                scalarLong("select count(*) from core.outbox_event where aggregate_id='" + first.id()
                        + "' and event_type='INTEGRATION_WEBHOOK_RECEIVED'"));
        assertThrows(
                IntegrationConflictException.class,
                () -> apiTransactions.required(
                        TENANT_A,
                        () -> webhooks.receive(
                                TENANT_A,
                                null,
                                new WebhookIngressService.ReceiveCommand(
                                        "PHASE06_WEBHOOK",
                                        providerEvent,
                                        "DELIVERY_RECEIPT",
                                        "{\"status\":\"changed\"}",
                                        "good"),
                                request -> true)));
        apiTransactions.required(TENANT_A, () -> {
            webhooks.markProcessed(TENANT_A, first.id());
            return null;
        });
        assertEquals(
                "PROCESSED",
                scalarString("select processing_status from integration.webhook_event where id='" + first.id() + "'"));

        String rejectedEvent = "evt-rejected-" + shortId();
        WebhookIngressService.WebhookResult rejected = apiTransactions.required(
                TENANT_A,
                () -> webhooks.receive(
                        TENANT_A,
                        null,
                        new WebhookIngressService.ReceiveCommand(
                                "PHASE06_WEBHOOK",
                                rejectedEvent,
                                "DELIVERY_RECEIPT",
                                "{\"status\":\"delivered\"}",
                                "bad"),
                        request -> "good".equals(request.signature())));
        assertFalse(rejected.signatureValid());
        assertEquals("REJECTED", rejected.status());
        assertEquals(
                0L, scalarLong("select count(*) from core.outbox_event where aggregate_id='" + rejected.id() + "'"));
        assertFalse(
                scalarBoolean("select has_table_privilege('sjg_api_runtime','integration.webhook_event','DELETE')"));

        DriverManagerDataSource tenantBData =
                new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_api_runtime", API_PASSWORD);
        JdbcTemplate tenantBJdbc = new JdbcTemplate(tenantBData);
        TenantTransactionRunner tenantBRunner =
                new TenantTransactionRunner(tenantBJdbc, new DataSourceTransactionManager(tenantBData));
        long visible = tenantBRunner.required(
                TENANT_B,
                () -> tenantBJdbc.queryForObject(
                        "select count(*) from integration.webhook_event where id=?", Long.class, first.id()));
        assertEquals(0L, visible, "webhook evidence must obey RLS");
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static boolean scalarBoolean(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms");
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getBoolean(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = admin("sjg_oms");
                Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void migrate(String database, String generated, String overlay) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:"
                + repoRoot.resolve("technical-platform/database/flyway").resolve(generated));
        if (overlay != null)
            locations.add("filesystem:"
                    + repoRoot.resolve("technical-platform/database/flyway-overlays")
                            .resolve(overlay));
        Flyway f = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id",
                        TENANT_A.toString(),
                        "sjg_tenant_code",
                        "PHASE06_INT_A",
                        "sjg_tenant_name",
                        "PHASE-06 Integration Tenant A"))
                .cleanDisabled(true)
                .load();
        assertTrue(f.migrate().success);
        f.validate();
    }

    private static Connection admin(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
    }

    private static String jdbcUrl(String database) {
        String url = postgres.getJdbcUrl();
        int q = url.indexOf('?');
        String suffix = q >= 0 ? url.substring(q) : "";
        String base = q >= 0 ? url.substring(0, q) : url;
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("AGENT.md"))
                    && Files.isDirectory(current.resolve("Knowledge Base"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
