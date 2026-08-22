package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.audit.PlatformAuditWriter;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.PlatformInboxService;
import cn.shangjingu.platform.core.event.PlatformOutboxHandler;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.trace.PlatformTraceContext;
import cn.shangjingu.platform.core.trace.PlatformTraceContextHolder;
import cn.shangjingu.platform.integration.IntegrationHttpClient;
import cn.shangjingu.platform.integration.WebhookIngressService;
import cn.shangjingu.platform.worker.PlatformOutboxWorker;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase06AuditTraceDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000661");
    private static final String WORKER_PASSWORD = "p06_trace_worker_" + shortId();
    private static final String AUDIT_PASSWORD = "p06_trace_audit_" + shortId();
    private static final String AUDITOR_PASSWORD = "p06_trace_auditor_" + shortId();
    private static PostgreSQLContainer<?> postgres;
    private static HttpServer provider;
    private static AtomicReference<String> providerCorrelation;
    private static AtomicReference<String> providerTrace;
    private static Path repoRoot;
    private static JdbcTemplate workerJdbc;
    private static TenantTransactionRunner workerTransactions;
    private static TransactionalOutboxService outbox;
    private static PlatformAuditWriter auditWriter;
    private static IntegrationHttpClient integration;

    @BeforeAll
    static void installDualDatabaseBaseline() throws Exception {
        providerCorrelation = new AtomicReference<>();
        providerTrace = new AtomicReference<>();
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/trace", exchange -> {
            providerCorrelation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            providerTrace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            byte[] response = "accepted".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Provider-Reference", "trace-provider-661");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        provider.start();

        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase06-trace-bootstrap-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres");
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE sjg_worker_runtime PASSWORD '" + WORKER_PASSWORD + "'");
            statement.execute("ALTER ROLE sjg_audit_writer PASSWORD '" + AUDIT_PASSWORD + "'");
            statement.execute("ALTER ROLE sjg_auditor PASSWORD '" + AUDITOR_PASSWORD + "'");
            statement.execute("CREATE DATABASE sjg_oms");
            statement.execute("CREATE DATABASE sjg_audit");
        }
        migrate("sjg_oms", "oms", "oms");
        migrate("sjg_audit", "audit", "audit");

        String providerUri = "http://127.0.0.1:" + provider.getAddress().getPort() + "/trace";
        executeAdmin(
                "sjg_oms",
                "insert into integration.endpoint(id,tenant_id,endpoint_code,system_name,protocol,endpoint_uri,auth_type,timeout_ms,enabled) values ('"
                        + UUID.randomUUID() + "','" + TENANT + "','PHASE06_TRACE_HTTP','Trace Provider','HTTP','"
                        + providerUri + "','NONE',3000,true)");
        executeAdmin(
                "sjg_oms",
                "insert into integration.endpoint(id,tenant_id,endpoint_code,system_name,protocol,endpoint_uri,auth_type,timeout_ms,enabled) values ('"
                        + UUID.randomUUID() + "','" + TENANT
                        + "','PHASE06_TRACE_WEBHOOK','Trace Webhook','HTTPS','https://trace.invalid','HMAC',3000,true)");

        DriverManagerDataSource workerData =
                new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_worker_runtime", WORKER_PASSWORD);
        workerJdbc = new JdbcTemplate(workerData);
        DataSourceTransactionManager workerTm = new DataSourceTransactionManager(workerData);
        workerTransactions = new TenantTransactionRunner(workerJdbc, workerTm);
        outbox = new TransactionalOutboxService(workerJdbc);
        integration = new IntegrationHttpClient(workerJdbc, workerTm, HttpClient.newHttpClient(), List.of());

        DriverManagerDataSource auditData =
                new DriverManagerDataSource(jdbcUrl("sjg_audit"), "sjg_audit_writer", AUDIT_PASSWORD);
        auditWriter = new PlatformAuditWriter(new JdbcTemplate(auditData), new DataSourceTransactionManager(auditData));
    }

    @AfterAll
    static void stopAll() {
        if (provider != null) provider.stop(0);
        if (postgres != null) postgres.stop();
    }

    @Test
    void asyncTracePersistsAcrossOutboxInboxProviderAndAudit() throws Exception {
        PlatformTraceContext trace = PlatformTraceContext.create();
        String eventKey = "trace-event-" + shortId();
        AtomicReference<PlatformTraceContext> observedByHandler = new AtomicReference<>();
        AtomicReference<UUID> auditId = new AtomicReference<>();

        PlatformOutboxHandler handler = new PlatformOutboxHandler() {
            @Override
            public String eventType() {
                return "PHASE06_TRACE_EVENT";
            }

            @Override
            public String consumerName() {
                return "phase06-trace-consumer";
            }

            @Override
            public void handle(cn.shangjingu.platform.core.event.PlatformOutboxEvent event) {
                observedByHandler.set(PlatformTraceContextHolder.currentOrNull());
                IntegrationHttpClient.CallResult result = integration.call(
                        TENANT,
                        new IntegrationHttpClient.CallCommand(
                                "PHASE06_TRACE_HTTP",
                                "trace-request-" + shortId(),
                                "trace-business-" + event.id(),
                                "{\"eventId\":\"" + event.id() + "\"}"));
                if (!result.success()) throw new IllegalStateException("trace provider did not accept request");
                auditId.set(auditWriter.appendCriticalOperation(new PlatformAuditWriter.OperationCommand(
                        TENANT,
                        null,
                        null,
                        "OUTBOX_PROVIDER_ACCEPTED",
                        "CORE_OUTBOX_EVENT",
                        event.id(),
                        event.eventKey())));
            }
        };
        PlatformOutboxWorker worker = new PlatformOutboxWorker(
                workerTransactions,
                workerJdbc,
                new PlatformInboxService(workerJdbc),
                List.of(handler),
                3,
                Duration.ofMillis(5),
                Duration.ofMillis(50));

        UUID outboxId;
        try (PlatformTraceContextHolder.Scope ignored = PlatformTraceContextHolder.open(trace)) {
            outboxId = workerTransactions.required(
                    TENANT,
                    () -> outbox.enqueue(new TransactionalOutboxService.Command(
                            TENANT,
                            null,
                            "PHASE06_TRACE",
                            UUID.randomUUID(),
                            "PHASE06_TRACE_EVENT",
                            1,
                            "{\"ok\":true}",
                            eventKey)));
        }
        assertEquals(1, worker.runOnce(1));
        assertEquals(
                trace, observedByHandler.get(), "Worker must restore durable Outbox trace before handler execution");
        assertNotNull(auditId.get());
        assertEquals(trace.correlationId(), providerCorrelation.get());
        assertEquals(trace.traceId(), providerTrace.get());

        assertTrace(
                "sjg_oms", "select correlation_id,trace_id from core.outbox_event where id='" + outboxId + "'", trace);
        assertEquals(
                "PUBLISHED",
                scalarString("sjg_oms", "select publish_status from core.outbox_event where id='" + outboxId + "'"));
        assertTrace(
                "sjg_oms",
                "select correlation_id,trace_id from core.inbox_event where event_key='" + eventKey + "'",
                trace);
        assertTrace(
                "sjg_oms",
                "select correlation_id,trace_id from integration.request_log where business_key='trace-business-"
                        + outboxId + "'",
                trace);
        assertTrace(
                "sjg_audit",
                "select correlation_id,trace_id from audit.operation_log where id='" + auditId.get() + "'",
                trace);
    }

    @Test
    void webhookTracePersistsIntoInboundEvidenceAndOutbox() throws Exception {
        PlatformTraceContext trace = PlatformTraceContext.create();
        WebhookIngressService webhooks = new WebhookIngressService(workerJdbc, outbox);
        String providerEventId = "trace-webhook-" + shortId();
        WebhookIngressService.WebhookResult result;
        try (PlatformTraceContextHolder.Scope ignored = PlatformTraceContextHolder.open(trace)) {
            result = workerTransactions.required(
                    TENANT,
                    () -> webhooks.receive(
                            TENANT,
                            null,
                            new WebhookIngressService.ReceiveCommand(
                                    "PHASE06_TRACE_WEBHOOK",
                                    providerEventId,
                                    "TRACE_RECEIPT",
                                    "{\"status\":\"accepted\"}",
                                    "valid"),
                            request -> "valid".equals(request.signature())));
        }
        assertTrue(result.signatureValid());
        assertFalse(result.duplicate());
        assertTrace(
                "sjg_oms",
                "select correlation_id,trace_id from integration.webhook_event where id='" + result.id() + "'",
                trace);
        assertTrace(
                "sjg_oms",
                "select correlation_id,trace_id from core.outbox_event where aggregate_id='" + result.id()
                        + "' and event_type='INTEGRATION_WEBHOOK_RECEIVED'",
                trace);
    }

    @Test
    void criticalAuditFailureIsFailClosedAndHistoryIsImmutable() throws Exception {
        assertFalse(scalarBoolean(
                "sjg_audit", "select has_table_privilege('sjg_audit_writer','audit.operation_log','UPDATE')"));
        assertFalse(scalarBoolean(
                "sjg_audit", "select has_table_privilege('sjg_audit_writer','audit.operation_log','DELETE')"));
        assertFalse(
                scalarBoolean("sjg_audit", "select has_table_privilege('sjg_auditor','audit.operation_log','INSERT')"));

        DriverManagerDataSource auditorData =
                new DriverManagerDataSource(jdbcUrl("sjg_audit"), "sjg_auditor", AUDITOR_PASSWORD);
        PlatformAuditWriter deniedWriter =
                new PlatformAuditWriter(new JdbcTemplate(auditorData), new DataSourceTransactionManager(auditorData));
        PlatformAuditWriter.OperationCommand command = new PlatformAuditWriter.OperationCommand(
                TENANT, null, null, "MUST_AUDIT", "TRACE_TEST", UUID.randomUUID(), "audit-denied-" + shortId());
        try (PlatformTraceContextHolder.Scope ignored =
                PlatformTraceContextHolder.open(PlatformTraceContext.create())) {
            assertThrows(
                    RuntimeException.class,
                    () -> deniedWriter.appendCriticalOperation(command),
                    "critical audit failure must escape to the caller and block continuation");
            assertFalse(
                    deniedWriter.tryAppendOperation(command),
                    "best-effort behavior is available only through the explicitly named noncritical method");
        }

        try (Connection connection = runtime("sjg_audit", "sjg_audit_writer", AUDIT_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("select set_config('app.tenant_id','" + TENANT + "',false)");
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate(
                            "update audit.operation_log set action='FORBIDDEN' where tenant_id='" + TENANT + "'"));
        }
    }

    private static void assertTrace(String database, String sql, PlatformTraceContext expected) throws SQLException {
        try (Connection connection = admin(database);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "trace evidence row missing for query: " + sql);
            assertEquals(expected.correlationId(), result.getString(1));
            assertEquals(expected.traceId(), result.getString(2));
        }
    }

    private static String scalarString(String database, String sql) throws SQLException {
        try (Connection connection = admin(database);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static boolean scalarBoolean(String database, String sql) throws SQLException {
        try (Connection connection = admin(database);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }

    private static void executeAdmin(String database, String sql) throws SQLException {
        try (Connection connection = admin(database);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id",
                        TENANT.toString(),
                        "sjg_tenant_code",
                        "PHASE06_TRACE",
                        "sjg_tenant_name",
                        "PHASE-06 Trace Tenant"))
                .cleanDisabled(true)
                .load();
        assertTrue(flyway.migrate().success);
        flyway.validate();
    }

    private static Connection admin(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection runtime(String database, String user, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), user, password);
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
