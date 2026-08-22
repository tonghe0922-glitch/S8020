package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.PlatformInboxService;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.notification.NotificationConflictException;
import cn.shangjingu.platform.notification.NotificationDeliveryHandler;
import cn.shangjingu.platform.notification.NotificationDeliveryProvider;
import cn.shangjingu.platform.notification.NotificationService;
import cn.shangjingu.platform.notification.NotificationTemplateRenderer;
import cn.shangjingu.platform.worker.PlatformOutboxWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
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

class Phase06NotificationDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000621");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000622");
    private static final String API_PASSWORD = "p06_notify_api_" + shortId();
    private static final String WORKER_PASSWORD = "p06_notify_worker_" + shortId();
    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;
    private static JdbcTemplate apiJdbc;
    private static JdbcTemplate workerJdbc;
    private static TenantTransactionRunner apiTransactions;
    private static TenantTransactionRunner workerTransactions;
    private static NotificationService notifications;

    @BeforeAll
    static void installBaseline() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase06-notification-bootstrap-" + UUID.randomUUID());
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
                + "','PHASE06_NOTIFY_B','PHASE-06 Notification Tenant B','ACTIVE','Asia/Shanghai')");
        UUID templateId = UUID.randomUUID();
        execute(
                "insert into notification.template(id,tenant_id,template_code,channel,title_template,body_template,variables_schema,enabled) values ('"
                        + templateId + "','" + TENANT_A + "','PHASE06_HELLO','EMAIL','Hello {{name}}','Code={{code}}',"
                        + "'{\"properties\":{\"name\":{},\"code\":{}},\"required\":[\"name\",\"code\"]}'::jsonb,true)");

        DriverManagerDataSource apiData =
                new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_api_runtime", API_PASSWORD);
        DriverManagerDataSource workerData =
                new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_worker_runtime", WORKER_PASSWORD);
        apiJdbc = new JdbcTemplate(apiData);
        workerJdbc = new JdbcTemplate(workerData);
        apiTransactions = new TenantTransactionRunner(apiJdbc, new DataSourceTransactionManager(apiData));
        workerTransactions = new TenantTransactionRunner(workerJdbc, new DataSourceTransactionManager(workerData));
        notifications = new NotificationService(
                apiJdbc, new TransactionalOutboxService(apiJdbc), new NotificationTemplateRenderer(new ObjectMapper()));
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void immediateMessageIsIdempotentAndDeliveredOnceThroughOutboxInbox() throws Exception {
        UUID recipient = UUID.randomUUID();
        NotificationService.CreateCommand command =
                command("request-immediate-" + shortId(), recipient, Map.of("name", "Nuo", "code", "A-01"), null);
        UUID messageId = apiTransactions.required(TENANT_A, () -> notifications.create(command));
        UUID replay = apiTransactions.required(TENANT_A, () -> notifications.create(command));
        assertEquals(messageId, replay);
        assertThrows(
                NotificationConflictException.class,
                () -> apiTransactions.required(
                        TENANT_A,
                        () -> notifications.create(command(
                                command.requestKey(), recipient, Map.of("name", "Changed", "code", "A-01"), null))));
        assertEquals("PENDING", scalarString("select status from notification.message where id='" + messageId + "'"));
        assertEquals(
                1L,
                scalarLong("select count(*) from core.outbox_event where aggregate_id='" + messageId
                        + "' and event_type='NOTIFICATION_SEND'"));

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> idempotencyKey = new AtomicReference<>();
        NotificationDeliveryProvider provider = provider(true, calls, idempotencyKey);
        NotificationDeliveryHandler handler = new NotificationDeliveryHandler(workerJdbc, List.of(provider));
        PlatformOutboxWorker worker = new PlatformOutboxWorker(
                workerTransactions,
                workerJdbc,
                new PlatformInboxService(workerJdbc),
                List.of(handler),
                3,
                Duration.ofMillis(5),
                Duration.ofMillis(20));
        assertEquals(1, worker.runOnce(8));
        assertEquals("SENT", scalarString("select status from notification.message where id='" + messageId + "'"));
        assertNotNull(scalarString("select sent_at::text from notification.message where id='" + messageId + "'"));
        assertEquals(
                "PUBLISHED",
                scalarString("select publish_status from core.outbox_event where aggregate_id='" + messageId + "'"));
        assertEquals(
                1L,
                scalarLong(
                        "select count(*) from core.inbox_event where consumer_name='notification-delivery' and result_code='SUCCESS' and event_key='notification-send:"
                                + messageId + "'"));
        assertEquals(1, calls.get());
        assertEquals("notification-send:" + messageId, idempotencyKey.get());
        assertEquals(0, worker.runOnce(8));
        assertEquals(1, calls.get(), "published notification must not call provider again");

        long crossTenant = workerTransactions.required(
                TENANT_B,
                () -> workerJdbc.queryForObject(
                        "select count(*) from notification.message where id=?", Long.class, messageId));
        assertEquals(0L, crossTenant, "RLS must hide another tenant notification message");
    }

    @Test
    void providerRejectionDoesNotFakeSentOrReceiptEvidence() throws Exception {
        UUID messageId = apiTransactions.required(
                TENANT_A,
                () -> notifications.create(command(
                        "request-reject-" + shortId(),
                        UUID.randomUUID(),
                        Map.of("name", "Reject", "code", "R-01"),
                        null)));
        AtomicInteger calls = new AtomicInteger();
        NotificationDeliveryHandler handler =
                new NotificationDeliveryHandler(workerJdbc, List.of(provider(false, calls, new AtomicReference<>())));
        PlatformOutboxWorker worker = new PlatformOutboxWorker(
                workerTransactions,
                workerJdbc,
                new PlatformInboxService(workerJdbc),
                List.of(handler),
                1,
                Duration.ofMillis(5),
                Duration.ofMillis(20));
        assertEquals(1, worker.runOnce(8));
        assertEquals("PENDING", scalarString("select status from notification.message where id='" + messageId + "'"));
        assertEquals(
                0L,
                scalarLong("select count(*) from notification.message where id='" + messageId
                        + "' and sent_at is not null"));
        assertEquals(
                "DEAD_LETTER",
                scalarString("select publish_status from core.outbox_event where aggregate_id='" + messageId + "'"));
        assertEquals(
                1L,
                scalarLong(
                        "select count(*) from integration.dead_letter where source_type='OUTBOX' and source_id=(select id::text from core.outbox_event where aggregate_id='"
                                + messageId + "')"));
        assertEquals(1, calls.get());
    }

    @Test
    void scheduledMessageEnqueuesOnlyWhenDue() throws Exception {
        Instant scheduled = Instant.now().plusSeconds(1);
        UUID messageId = apiTransactions.required(
                TENANT_A,
                () -> notifications.create(command(
                        "request-scheduled-" + shortId(),
                        UUID.randomUUID(),
                        Map.of("name", "Later", "code", "L-01"),
                        scheduled)));
        assertEquals(0L, scalarLong("select count(*) from core.outbox_event where aggregate_id='" + messageId + "'"));
        Thread.sleep(1100L);
        int enqueued = apiTransactions.required(TENANT_A, () -> notifications.enqueueDue(TENANT_A, null, 20));
        assertEquals(1, enqueued);
        assertEquals(
                1L,
                scalarLong("select count(*) from core.outbox_event where aggregate_id='" + messageId
                        + "' and event_key='notification-send:" + messageId + "'"));
        assertEquals(0, apiTransactions.required(TENANT_A, () -> notifications.enqueueDue(TENANT_A, null, 20)));
        execute("update core.outbox_event set publish_status='DEAD_LETTER' where aggregate_id='" + messageId
                + "' and publish_status='PENDING'");
    }

    private static NotificationService.CreateCommand command(
            String requestKey, UUID recipient, Map<String, String> vars, Instant scheduled) {
        return new NotificationService.CreateCommand(
                TENANT_A, null, requestKey, "PHASE06_HELLO", "EMAIL", "USER", recipient, vars, scheduled);
    }

    private static NotificationDeliveryProvider provider(
            boolean accepted, AtomicInteger calls, AtomicReference<String> idempotencyKey) {
        return new NotificationDeliveryProvider() {
            @Override
            public String channel() {
                return "EMAIL";
            }

            @Override
            public DeliveryResult deliver(DeliveryRequest request) {
                calls.incrementAndGet();
                idempotencyKey.set(request.idempotencyKey());
                return new DeliveryResult(accepted, accepted ? "provider-ref" : null);
            }
        };
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms");
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:"
                + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null)
            locations.add("filesystem:"
                    + repoRoot.resolve("technical-platform/database/flyway-overlays")
                            .resolve(overlayFolder));
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id",
                        TENANT_A.toString(),
                        "sjg_tenant_code",
                        "PHASE06_NOTIFY_A",
                        "sjg_tenant_name",
                        "PHASE-06 Notification Tenant A"))
                .cleanDisabled(true)
                .load();
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
