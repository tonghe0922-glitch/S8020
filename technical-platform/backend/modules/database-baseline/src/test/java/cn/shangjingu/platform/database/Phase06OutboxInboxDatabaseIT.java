package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.OutboxConflictException;
import cn.shangjingu.platform.core.event.PlatformInboxService;
import cn.shangjingu.platform.core.event.PlatformOutboxEvent;
import cn.shangjingu.platform.core.event.PlatformOutboxHandler;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.worker.PlatformOutboxWorker;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class Phase06OutboxInboxDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000601");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000602");
    private static final String API_PASSWORD = "p06_api_" + shortId();
    private static final String WORKER_PASSWORD = "p06_worker_" + shortId();
    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;
    private static JdbcTemplate apiJdbc;
    private static JdbcTemplate workerJdbc;
    private static TenantTransactionRunner apiTransactions;
    private static TenantTransactionRunner workerTransactions;
    private static TransactionalOutboxService outbox;
    private static PlatformInboxService inbox;

    @BeforeAll
    static void installApprovedBaseline() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase06-bootstrap-" + UUID.randomUUID());
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
                + "','PHASE06_B','PHASE-06 Tenant B','ACTIVE','Asia/Shanghai')");

        DriverManagerDataSource apiDataSource =
                new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_api_runtime", API_PASSWORD);
        apiJdbc = new JdbcTemplate(apiDataSource);
        apiTransactions = new TenantTransactionRunner(apiJdbc, new DataSourceTransactionManager(apiDataSource));
        outbox = new TransactionalOutboxService(apiJdbc);

        DriverManagerDataSource workerDataSource =
                new DriverManagerDataSource(jdbcUrl("sjg_oms"), "sjg_worker_runtime", WORKER_PASSWORD);
        workerJdbc = new JdbcTemplate(workerDataSource);
        workerTransactions =
                new TenantTransactionRunner(workerJdbc, new DataSourceTransactionManager(workerDataSource));
        inbox = new PlatformInboxService(workerJdbc);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void outboxSharesCallerTransactionAndEventKeyIsContentSafe() throws Exception {
        String rollbackKey = "c2-rollback-" + shortId();
        assertThrows(
                IllegalStateException.class,
                () -> apiTransactions.required(TENANT_A, () -> {
                    outbox.enqueue(command(TENANT_A, "C2_ATOMIC", rollbackKey, "{\"value\":1}"));
                    throw new IllegalStateException("force caller rollback");
                }));
        assertEquals(0L, scalarLong("select count(*) from core.outbox_event where event_key='" + rollbackKey + "'"));

        String key = "c2-idem-" + shortId();
        TransactionalOutboxService.Command original = command(TENANT_A, "C2_ATOMIC", key, "{\"value\":1}");
        UUID first = apiTransactions.required(TENANT_A, () -> outbox.enqueue(original));
        UUID replay = apiTransactions.required(TENANT_A, () -> outbox.enqueue(original));
        assertEquals(first, replay);
        assertEquals(1L, scalarLong("select count(*) from core.outbox_event where event_key='" + key + "'"));
        assertThrows(
                OutboxConflictException.class,
                () -> apiTransactions.required(
                        TENANT_A, () -> outbox.enqueue(command(TENANT_A, "C2_ATOMIC", key, "{\"value\":2}"))));

        long crossTenant = workerTransactions.required(
                TENANT_B,
                () -> workerJdbc.queryForObject(
                        "select count(*) from core.outbox_event where event_key=?", Long.class, key));
        assertEquals(0L, crossTenant, "worker RLS must hide another tenant's outbox events");
    }

    @Test
    void inboxAdvisoryLockSerializesConcurrentDuplicateConsumerEvent() throws Exception {
        String eventKey = "c2-inbox-race-" + shortId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> claimInbox(eventKey, ready, fire));
            Future<Boolean> second = pool.submit(() -> claimInbox(eventKey, ready, fire));
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            fire.countDown();
            long firstProcessors = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)).stream()
                    .filter(Boolean::booleanValue)
                    .count();
            assertEquals(1L, firstProcessors, "advisory lock must allow exactly one first Inbox processor");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                1L,
                scalarLong("select count(*) from core.inbox_event where event_key='" + eventKey
                        + "' and consumer_name='c2-race-consumer' and result_code='SUCCESS'"));
    }

    @Test
    void failedWorkerTransactionRollsBackInboxAndNewWorkerInstanceCanResume() throws Exception {
        String eventKey = "c2-restart-" + shortId();
        UUID eventId = apiTransactions.required(
                TENANT_A, () -> outbox.enqueue(command(TENANT_A, "C2_RESTART", eventKey, "{\"attempt\":\"initial\"}")));

        PlatformOutboxHandler failing = handler("C2_RESTART", "c2-restart-consumer", event -> {
            workerJdbc.update(
                    "update core.outbox_event set payload=payload || '{\"transient\":true}'::jsonb where id=?",
                    event.id());
            throw new IllegalStateException("simulated worker crash");
        });
        PlatformOutboxWorker firstWorker = worker(List.of(failing), 3);
        assertEquals(1, firstWorker.runOnce(8));
        assertEquals(
                "PENDING", scalarString("select publish_status from core.outbox_event where id='" + eventId + "'"));
        assertEquals(1L, scalarLong("select retry_count from core.outbox_event where id='" + eventId + "'"));
        assertEquals(0L, scalarLong("select count(*) from core.inbox_event where event_key='" + eventKey + "'"));
        assertFalse(
                scalarBoolean("select payload ? 'transient' from core.outbox_event where id='" + eventId + "'"),
                "handler DB changes must roll back when the worker transaction fails");

        Thread.sleep(10L);
        AtomicInteger handled = new AtomicInteger();
        PlatformOutboxHandler succeeding = handler("C2_RESTART", "c2-restart-consumer", event -> {
            handled.incrementAndGet();
            workerJdbc.update(
                    "update core.outbox_event set payload=payload || '{\"handled\":true}'::jsonb where id=?",
                    event.id());
        });
        PlatformOutboxWorker restartedWorker = worker(List.of(succeeding), 3);
        assertEquals(1, restartedWorker.runOnce(8));
        assertEquals(1, handled.get());
        assertEquals(
                "PUBLISHED", scalarString("select publish_status from core.outbox_event where id='" + eventId + "'"));
        assertEquals(
                1L,
                scalarLong("select count(*) from core.inbox_event where event_key='" + eventKey
                        + "' and consumer_name='c2-restart-consumer' and result_code='SUCCESS'"));
        assertTrue(scalarBoolean("select payload ? 'handled' from core.outbox_event where id='" + eventId + "'"));
        assertEquals(0, restartedWorker.runOnce(8), "published event must not execute again after worker restart");
    }

    @Test
    void repeatedFailureUsesExponentialRetryAndPersistsDeadLetterAtThreshold() throws Exception {
        String eventKey = "c2-dlq-" + shortId();
        UUID eventId = apiTransactions.required(
                TENANT_A, () -> outbox.enqueue(command(TENANT_A, "C2_DLQ", eventKey, "{\"operation\":\"fail\"}")));
        PlatformOutboxHandler alwaysFail = handler("C2_DLQ", "c2-dlq-consumer", event -> {
            throw new IllegalArgumentException("permanent provider failure");
        });
        PlatformOutboxWorker worker = worker(List.of(alwaysFail), 2);

        assertEquals(1, worker.runOnce(8));
        assertEquals(
                "PENDING", scalarString("select publish_status from core.outbox_event where id='" + eventId + "'"));
        assertEquals(1L, scalarLong("select retry_count from core.outbox_event where id='" + eventId + "'"));
        Thread.sleep(10L);
        assertEquals(1, worker.runOnce(8));
        assertEquals(
                "DEAD_LETTER", scalarString("select publish_status from core.outbox_event where id='" + eventId + "'"));
        assertEquals(2L, scalarLong("select retry_count from core.outbox_event where id='" + eventId + "'"));
        assertEquals(
                1L,
                scalarLong("select count(*) from integration.dead_letter where source_type='OUTBOX' and source_id='"
                        + eventId + "' and status='OPEN'"));
        assertEquals(0L, scalarLong("select count(*) from core.inbox_event where event_key='" + eventKey + "'"));
        assertEquals(0, worker.runOnce(8), "dead-lettered event must not be dispatched again");
    }

    private static boolean claimInbox(String eventKey, CountDownLatch ready, CountDownLatch fire) throws Exception {
        ready.countDown();
        assertTrue(fire.await(20, TimeUnit.SECONDS));
        return workerTransactions.required(TENANT_A, () -> {
            boolean first = inbox.claim(TENANT_A, "c2-race-consumer", eventKey);
            if (first) inbox.complete(TENANT_A, "c2-race-consumer", eventKey);
            return first;
        });
    }

    private static PlatformOutboxWorker worker(List<PlatformOutboxHandler> handlers, int maxAttempts) {
        return new PlatformOutboxWorker(
                workerTransactions,
                workerJdbc,
                inbox,
                handlers,
                maxAttempts,
                Duration.ofMillis(1),
                Duration.ofMillis(8));
    }

    private static PlatformOutboxHandler handler(
            String eventType, String consumerName, java.util.function.Consumer<PlatformOutboxEvent> action) {
        return new PlatformOutboxHandler() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public String consumerName() {
                return consumerName;
            }

            @Override
            public void handle(PlatformOutboxEvent event) {
                action.accept(event);
            }
        };
    }

    private static TransactionalOutboxService.Command command(
            UUID tenantId, String eventType, String key, String payload) {
        return new TransactionalOutboxService.Command(
                tenantId, null, "C2_TEST", UUID.randomUUID(), eventType, 1, payload, key);
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

    private static boolean scalarBoolean(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
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
        if (overlayFolder != null) {
            locations.add("filesystem:"
                    + repoRoot.resolve("technical-platform/database/flyway-overlays")
                            .resolve(overlayFolder));
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT_A.toString(),
                        "sjg_tenant_code", "PHASE06_A",
                        "sjg_tenant_name", "PHASE-06 Tenant A"))
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
