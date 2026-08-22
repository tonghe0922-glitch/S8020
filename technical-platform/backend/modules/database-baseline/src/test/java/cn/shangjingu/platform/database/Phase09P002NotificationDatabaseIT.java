package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.PlatformOutboxEvent;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.notification.NotificationService;
import cn.shangjingu.platform.notification.NotificationTemplateRenderer;
import cn.shangjingu.platform.worker.Phase09P002NotificationHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real PostgreSQL checkpoint for P002 Outbox -> durable in-app notification handling. */
class Phase09P002NotificationDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000991");
    private static final UUID RECIPIENT = UUID.fromString("30000000-0000-0000-0000-000000000991");
    private static final UUID AGGREGATE = UUID.fromString("90000000-0000-0000-0000-000000000911");
    private static final UUID EVENT = UUID.fromString("90000000-0000-0000-0000-000000000912");
    private static final String WORKER_PASSWORD = "p09_notify_" + shortId();

    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;
    private static JdbcTemplate jdbc;
    private static TenantTransactionRunner transactions;
    private static Phase09P002NotificationHandler handler;

    @BeforeAll
    static void installApprovedBaseline() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase09-notify-bootstrap-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres");
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE sjg_worker_runtime PASSWORD '" + WORKER_PASSWORD + "'");
            statement.execute("CREATE DATABASE sjg_oms");
        }
        migrate("sjg_oms", "oms", "oms");

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(jdbcUrl("sjg_oms"));
        dataSource.setUsername("sjg_worker_runtime");
        dataSource.setPassword(WORKER_PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TenantTransactionRunner(jdbc, transactionManager);
        ObjectMapper mapper = new ObjectMapper();
        TransactionalOutboxService outbox = new TransactionalOutboxService(jdbc);
        NotificationService notifications =
                new NotificationService(jdbc, outbox, new NotificationTemplateRenderer(mapper));
        handler = new Phase09P002NotificationHandler(notifications, jdbc, mapper);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void p002EventCreatesOneRenderedMessageAndOneSendOutboxAcrossExactReplay() throws Exception {
        PlatformOutboxEvent event = event(
                EVENT,
                "{\"businessNo\":\"P002-NOTIFY-001\",\"event\":\"SUBMITTED\",\"nodeCode\":\"S03\","
                        + "\"recipientEmployeeIds\":[\"" + RECIPIENT + "\",\"" + RECIPIENT + "\"]}");

        handle(event);
        handle(event);

        assertEquals(
                1L,
                scalarLong(
                        "select count(*) from notification.message where tenant_id='" + TENANT + "' and recipient_id='"
                                + RECIPIENT + "' and channel='IN_APP' and status='PENDING' and not is_deleted"));
        assertEquals(
                "权限申请进度：业务负责人确认",
                scalarString("select title from notification.message where tenant_id='" + TENANT
                        + "' and recipient_id='" + RECIPIENT + "' and not is_deleted"));
        assertEquals(
                "权限申请 P002-NOTIFY-001 当前状态：业务负责人确认（事件 SUBMITTED）。",
                scalarString("select body from notification.message where tenant_id='" + TENANT + "' and recipient_id='"
                        + RECIPIENT + "' and not is_deleted"));
        assertEquals(
                1L,
                scalarLong(
                        "select count(*) from core.outbox_event where tenant_id='" + TENANT
                                + "' and aggregate_type='NOTIFICATION_MESSAGE' and event_type='NOTIFICATION_SEND' and not is_deleted"));
        assertEquals(
                1L,
                scalarLong(
                        "select count(*) from notification.template where tenant_id='" + TENANT
                                + "' and template_code='P002_PERMISSION_EVENT' and channel='IN_APP' and enabled and not is_deleted"));
    }

    @Test
    void unsupportedSourceNodeFailsClosedWithoutCreatingNotification() throws Exception {
        UUID invalidEventId = UUID.fromString("90000000-0000-0000-0000-000000000913");
        PlatformOutboxEvent invalid = event(
                invalidEventId,
                "{\"businessNo\":\"P002-NOTIFY-BAD\",\"event\":\"BROKEN\",\"nodeCode\":\"S99\","
                        + "\"recipientEmployeeIds\":[\"" + RECIPIENT + "\"]}");

        assertThrows(IllegalArgumentException.class, () -> handle(invalid));
        assertEquals(
                0L,
                scalarLong("select count(*) from notification.message where tenant_id='" + TENANT
                        + "' and body like '%P002-NOTIFY-BAD%' and not is_deleted"));
        assertEquals(
                0L,
                scalarLong(
                        "select count(*) from core.outbox_event where tenant_id='" + TENANT
                                + "' and aggregate_type='NOTIFICATION_MESSAGE' and payload::text like '%P002-NOTIFY-BAD%' and not is_deleted"));
    }

    private static void handle(PlatformOutboxEvent event) {
        transactions.required(TENANT, () -> {
            handler.handle(event);
            return null;
        });
    }

    private static PlatformOutboxEvent event(UUID id, String payload) {
        Instant now = Instant.now();
        return new PlatformOutboxEvent(
                id,
                TENANT,
                Phase09P002NotificationHandler.AGGREGATE_TYPE,
                AGGREGATE,
                Phase09P002NotificationHandler.EVENT_TYPE,
                1,
                payload,
                "p002-notification-it:" + id,
                null,
                null,
                0,
                now,
                now);
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

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:"
                + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) {
            locations.add("filesystem:"
                    + repoRoot.resolve("technical-platform/database/flyway-overlays")
                            .resolve(overlayFolder));
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT.toString(),
                        "sjg_tenant_code", "PHASE09_P002_NOTIFY",
                        "sjg_tenant_name", "PHASE-09 P002 Notification Tenant"))
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
