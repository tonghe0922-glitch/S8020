package cn.shangjingu.platform.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.shangjingu.platform.audit.PlatformAuditWriter;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.worker.Phase09P002ExpiryWorker;
import cn.shangjingu.platform.workflow.CoreWorkflowIdempotency;
import cn.shangjingu.platform.workflow.FailClosedTransitionConditionEvaluator;
import cn.shangjingu.platform.workflow.JdbcWorkflowRuntimeRepository;
import cn.shangjingu.platform.workflow.WorkflowSystemActionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PHASE-09 / P002 real PostgreSQL checkpoint for automatic permission expiry.
 *
 * <p>This deliberately runs the production expiry worker against the approved Flyway schema and
 * source-backed P002 workflow graph. It protects the worker from drifting away from the grant table
 * contract and proves both the successful AUTO_EXPIRE path and the retry/DLQ fail-closed path.</p>
 */
class Phase09P002ExpiryDatabaseIT {
    private static final String POSTGRES_IMAGE = "postgres:16.14-alpine3.24";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000992");
    private static final UUID CENTER = UUID.fromString("10000000-0000-0000-0000-000000000992");
    private static final UUID POSITION = UUID.fromString("20000000-0000-0000-0000-000000000992");
    private static final String TARGET_JOB_CODE = "PHASE09_P002_POS";
    private static final UUID EMPLOYEE = UUID.fromString("30000000-0000-0000-0000-000000000992");
    private static final UUID USER = UUID.fromString("50000000-0000-0000-0000-000000000992");
    private static final UUID IDENTITY = UUID.fromString("60000000-0000-0000-0000-000000000992");
    private static final UUID ROLE = UUID.fromString("70000000-0000-0000-0000-000000000992");
    private static final UUID USER_ROLE = UUID.fromString("71000000-0000-0000-0000-000000000992");

    private static final UUID SUCCESS_REQUEST = UUID.fromString("90000000-0000-0000-0000-000000000921");
    private static final UUID SUCCESS_GRANT = UUID.fromString("90000000-0000-0000-0000-000000000922");
    private static final UUID SUCCESS_INSTANCE = UUID.fromString("90000000-0000-0000-0000-000000000923");
    private static final UUID SUCCESS_TASK = UUID.fromString("90000000-0000-0000-0000-000000000924");
    private static final UUID DLQ_REQUEST = UUID.fromString("90000000-0000-0000-0000-000000000925");
    private static final UUID DLQ_GRANT = UUID.fromString("90000000-0000-0000-0000-000000000926");

    private static final String WORKER_PASSWORD = "p09_worker_" + shortId();
    private static final String AUDIT_PASSWORD = "p09_audit_" + shortId();
    private static PostgreSQLContainer<?> postgres;
    private static Path repoRoot;
    private static JdbcTemplate workerJdbc;
    private static DataSourceTransactionManager workerTx;
    private static TenantTransactionRunner workerTransactions;
    private static JdbcTemplate auditJdbc;
    private static DataSourceTransactionManager auditTx;

    @BeforeAll
    static void installApprovedBaseline() throws Exception {
        repoRoot = findRepoRoot();
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("postgres")
                .withUsername("postgres")
                .withPassword("phase09-bootstrap-" + UUID.randomUUID());
        postgres.start();
        migrate("postgres", "cluster", null);
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE sjg_worker_runtime PASSWORD '" + WORKER_PASSWORD + "'");
            statement.execute("ALTER ROLE sjg_audit_writer PASSWORD '" + AUDIT_PASSWORD + "'");
            statement.execute("CREATE DATABASE sjg_oms");
            statement.execute("CREATE DATABASE sjg_audit");
        }
        migrate("sjg_oms", "oms", "oms");
        migrate("sjg_audit", "audit", "audit");
        seedActorAndGrantTarget();

        DriverManagerDataSource workerDataSource = dataSource("sjg_oms", "sjg_worker_runtime", WORKER_PASSWORD);
        workerJdbc = new JdbcTemplate(workerDataSource);
        workerTx = new DataSourceTransactionManager(workerDataSource);
        workerTransactions = new TenantTransactionRunner(workerJdbc, workerTx);

        DriverManagerDataSource auditDataSource = dataSource("sjg_audit", "sjg_audit_writer", AUDIT_PASSWORD);
        auditJdbc = new JdbcTemplate(auditDataSource);
        auditTx = new DataSourceTransactionManager(auditDataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void autoExpireMovesWorkflowRevokesGrantAndEmitsMandatoryFacts() throws Exception {
        seedSuccessfulExpiry();
        Phase09P002ExpiryWorker worker = worker(3);

        assertEquals(1, worker.runOnce(1));
        assertEquals("S08", scalarString("sjg_oms", "select current_node_code from workflow.wf_instance where id='" + SUCCESS_INSTANCE + "'"));
        assertEquals("到期/调岗/离职回收", scalarString("sjg_oms", "select status from iam.permission_request where id='" + SUCCESS_REQUEST + "'"));
        assertEquals("REVOKED", scalarString("sjg_oms", "select grant_status from iam.permission_request_grant where id='" + SUCCESS_GRANT + "'"));
        assertEquals("AUTO_EXPIRE", scalarString("sjg_oms", "select revoke_source from iam.permission_request_grant where id='" + SUCCESS_GRANT + "'"));
        assertNull(scalarNullableString("sjg_oms", "select revoked_by::text from iam.permission_request_grant where id='" + SUCCESS_GRANT + "'"));
        assertNull(scalarNullableString("sjg_oms", "select updated_by::text from iam.permission_request_grant where id='" + SUCCESS_GRANT + "'"));
        assertNotNull(scalarNullableString("sjg_oms", "select effective_end_at::text from iam.user_role where id='" + USER_ROLE + "'"));

        assertEquals(1L, scalarLong("sjg_oms", "select count(*) from workflow.wf_action_log where instance_id='" + SUCCESS_INSTANCE
                + "' and action_code='AUTO_EXPIRE' and operator_id is null and not is_deleted"));
        assertEquals(1L, scalarLong("sjg_oms", "select count(*) from workflow.wf_task where instance_id='" + SUCCESS_INSTANCE
                + "' and node_code='S07' and status='COMPLETED' and result_code='AUTO_EXPIRE' and not is_deleted"));
        assertEquals(1L, scalarLong("sjg_oms", "select count(*) from workflow.wf_task where instance_id='" + SUCCESS_INSTANCE
                + "' and node_code='S08' and status='PENDING' and not is_deleted"));
        assertEquals(1L, scalarLong("sjg_audit", "select count(*) from audit.operation_log where resource_id='" + SUCCESS_REQUEST
                + "' and action='P002_AUTO_EXPIRE' and actor_id is null"));
        assertEquals(1L, scalarLong("sjg_oms", "select count(*) from core.outbox_event where aggregate_id='" + SUCCESS_REQUEST
                + "' and event_type='P002_PERMISSION_REQUEST_EVENT' and event_key='p002:" + SUCCESS_REQUEST + ":auto_expired:S08'"));
        assertTrue(scalarBoolean("sjg_oms", "select payload->>'event'='AUTO_EXPIRED' from core.outbox_event where aggregate_id='"
                + SUCCESS_REQUEST + "' and event_type='P002_PERMISSION_REQUEST_EVENT'"));

        assertEquals(0, worker.runOnce(1), "a revoked grant must never be auto-expired twice");
    }

    @Test
    void expiryFailurePersistsRetryDlqAndCriticalAuditWithoutInventingActor() throws Exception {
        seedDlqExpiry();
        Phase09P002ExpiryWorker worker = worker(1);

        assertEquals(1, worker.runOnce(1));
        assertEquals("ACTIVE", scalarString("sjg_oms", "select grant_status from iam.permission_request_grant where id='" + DLQ_GRANT + "'"));
        assertEquals(1L, scalarLong("sjg_oms", "select expiry_retry_count from iam.permission_request_grant where id='" + DLQ_GRANT + "'"));
        assertNotNull(scalarNullableString("sjg_oms", "select expiry_dead_lettered_at::text from iam.permission_request_grant where id='" + DLQ_GRANT + "'"));
        assertTrue(scalarString("sjg_oms", "select expiry_last_error from iam.permission_request_grant where id='" + DLQ_GRANT + "'")
                .contains("missing workflow or user-role linkage"));
        assertEquals(1L, scalarLong("sjg_oms", "select count(*) from integration.dead_letter where source_type='P002_AUTO_EXPIRE'"
                + " and source_id='" + DLQ_GRANT + "' and status='OPEN' and not is_deleted"));
        assertEquals(1L, scalarLong("sjg_audit", "select count(*) from audit.operation_log where resource_id='" + DLQ_REQUEST
                + "' and action='P002_AUTO_EXPIRE_DEAD_LETTER' and actor_id is null"));
        assertEquals(0L, scalarLong("sjg_oms", "select count(*) from core.outbox_event where aggregate_id='" + DLQ_REQUEST
                + "' and event_type='P002_PERMISSION_REQUEST_EVENT'"));
        assertEquals(0, worker.runOnce(1), "dead-lettered grant must not remain eligible for polling");
    }

    @Test
    void grantSchemaAndExpiryIndexMatchWorkerContract() throws Exception {
        assertFalse(scalarBoolean("sjg_oms", "select exists(select 1 from information_schema.columns where table_schema='iam'"
                + " and table_name='permission_request_grant' and column_name='is_deleted')"));
        assertTrue(scalarBoolean("sjg_oms", "select exists(select 1 from pg_indexes where schemaname='iam'"
                + " and tablename='permission_request_grant' and indexname='ix_permission_request_grant_expiry_due')"));
        assertFalse(scalarBoolean("sjg_audit", "select exists(select 1 from information_schema.columns where table_schema='audit'"
                + " and table_name='operation_log' and column_name='is_deleted')"));
    }

    private static Phase09P002ExpiryWorker worker(int maxAttempts) {
        ObjectMapper mapper = new ObjectMapper();
        WorkflowSystemActionService systemActions = new WorkflowSystemActionService(
                new JdbcWorkflowRuntimeRepository(workerJdbc, mapper),
                new CoreWorkflowIdempotency(new IdempotencyRegistry(workerJdbc)),
                new FailClosedTransitionConditionEvaluator(),
                mapper);
        return new Phase09P002ExpiryWorker(
                workerTransactions,
                workerJdbc,
                systemActions,
                new TransactionalOutboxService(workerJdbc),
                new PlatformAuditWriter(auditJdbc, auditTx),
                mapper,
                maxAttempts,
                Duration.ofMillis(1),
                Duration.ofMillis(8));
    }

    private static void seedActorAndGrantTarget() throws SQLException {
        execute("insert into org.organization(id,tenant_id,org_code,org_name,org_type,path,status) values ('" + CENTER
                + "','" + TENANT + "','PHASE09_P002_CENTER','Synthetic P002 Center','CENTER','phase09_p002_center'::ltree,'ACTIVE')");
        execute("insert into org.position(id,tenant_id,position_code,position_name,org_id,status) values ('" + POSITION
                + "','" + TENANT + "','" + TARGET_JOB_CODE + "','Synthetic P002 Position','" + CENTER + "','ACTIVE')");
        execute("insert into org.employee(id,tenant_id,employee_no,person_name,employment_status,hire_date,primary_org_id,primary_position_id) values ('"
                + EMPLOYEE + "','" + TENANT + "','P002-E001','P002 Expiry Owner','ACTIVE',current_date-30,'" + CENTER + "','" + POSITION + "')");
        execute("insert into iam.user_account(id,tenant_id,login_name,password_hash,status,mfa_level) values ('" + USER + "','" + TENANT
                + "','phase09.p002.expiry','test-only-hash','ACTIVE',0)");
        execute("insert into iam.user_identity(id,tenant_id,user_id,employee_id,identity_type,identity_name,org_id,position_id,is_primary,effective_start_at) values ('"
                + IDENTITY + "','" + TENANT + "','" + USER + "','" + EMPLOYEE + "','EMPLOYEE','P002 Expiry Identity','" + CENTER + "','"
                + POSITION + "',true,now()-interval '30 days')");
        execute("insert into iam.role(id,tenant_id,role_code,role_name,role_type,enabled) values ('" + ROLE + "','" + TENANT
                + "','PHASE09_P002_EXPIRY_ROLE','P002 Expiry Role','PLATFORM',true)");
        execute("insert into iam.user_role(id,tenant_id,created_by,updated_by,user_id,identity_id,role_id,effective_start_at,grant_source) values ('"
                + USER_ROLE + "','" + TENANT + "','" + EMPLOYEE + "','" + EMPLOYEE + "','" + USER + "','" + IDENTITY + "','" + ROLE
                + "',now()-interval '30 days','P002_PERMISSION_REQUEST')");
    }

    private static void seedSuccessfulExpiry() throws SQLException {
        // permission_request.workflow_instance_id is a real FK. Seed the workflow instance first;
        // business_object_id is a generic UUID reference and does not require the business row yet.
        execute("insert into workflow.wf_instance(id,tenant_id,instance_no,definition_id,version_id,process_code,business_object_type,business_object_id,"
                + "business_object_no,title,initiator_id,current_node_code,status,priority,started_at,context_snapshot) "
                + "select '" + SUCCESS_INSTANCE + "','" + TENANT + "','WFI-P002-EXPIRY-SUCCESS',d.id,v.id,'P002','iam.permission_request','"
                + SUCCESS_REQUEST + "','P002-EXPIRY-SUCCESS','P002 automatic expiry success','" + EMPLOYEE + "','S07','RUNNING','NORMAL',"
                + "now()-interval '10 days','{}'::jsonb from workflow.wf_definition d join workflow.wf_version v on v.definition_id=d.id and v.tenant_id=d.tenant_id "
                + "where d.tenant_id='" + TENANT + "' and d.process_code='P002' and d.enabled and not d.is_deleted and v.status='PUBLISHED' and not v.is_deleted order by v.version_no desc limit 1");
        execute("insert into iam.permission_request(id,tenant_id,business_no,workflow_instance_id,status,version_no,created_by,updated_by,"
                + "source_channel,business_date,subject,reason,priority,risk_level,owner_center_id,owner_employee_id,planned_start_at,planned_finish_at,"
                + "employee_event_type,employment_type,person_name,person_no,planned_effective_date,target_job_id) values ('"
                + SUCCESS_REQUEST + "','" + TENANT + "','P002-EXPIRY-SUCCESS','" + SUCCESS_INSTANCE + "','定期复核',7,'" + EMPLOYEE + "','" + EMPLOYEE
                + "','TEST',current_date,'P002 automatic expiry success','integration checkpoint','NORMAL','NORMAL','" + CENTER + "','" + EMPLOYEE
                + "',now()-interval '10 days',now()-interval '1 hour','PERMISSION_REQUEST','CURRENT_APPOINTMENT','P002 Expiry Owner','P002-E001',current_date-10,'"
                + TARGET_JOB_CODE + "')");
        execute("insert into workflow.wf_task(id,tenant_id,instance_id,task_no,node_code,task_type,status,received_at) values ('" + SUCCESS_TASK + "','"
                + TENANT + "','" + SUCCESS_INSTANCE + "','WFT-P002-EXPIRY-S07','S07','TASK','PENDING',now()-interval '1 day')");
        execute("insert into iam.permission_request_grant(id,tenant_id,permission_request_id,target_user_id,target_identity_id,requested_role_id,user_role_id,"
                + "grant_status,effective_start_at,effective_end_at,executed_by,executed_at,version_no,created_by,updated_by) values ('" + SUCCESS_GRANT + "','"
                + TENANT + "','" + SUCCESS_REQUEST + "','" + USER + "','" + IDENTITY + "','" + ROLE + "','" + USER_ROLE
                + "','ACTIVE',now()-interval '10 days',now()-interval '1 hour','" + EMPLOYEE + "',now()-interval '10 days',1,'" + EMPLOYEE + "','" + EMPLOYEE + "')");
    }

    private static void seedDlqExpiry() throws SQLException {
        execute("insert into iam.permission_request(id,tenant_id,business_no,workflow_instance_id,status,version_no,created_by,updated_by,"
                + "source_channel,business_date,subject,reason,priority,risk_level,owner_center_id,owner_employee_id,planned_start_at,planned_finish_at,"
                + "employee_event_type,employment_type,person_name,person_no,planned_effective_date,target_job_id) values ('"
                + DLQ_REQUEST + "','" + TENANT + "','P002-EXPIRY-DLQ',null,'定期复核',3,'" + EMPLOYEE + "','" + EMPLOYEE
                + "','TEST',current_date,'P002 automatic expiry DLQ','missing workflow linkage','NORMAL','NORMAL','" + CENTER + "','" + EMPLOYEE
                + "',now()-interval '5 days',now()-interval '2 hours','PERMISSION_REQUEST','CURRENT_APPOINTMENT','P002 Expiry Owner','P002-E001',current_date-5,'"
                + TARGET_JOB_CODE + "')");
        execute("insert into iam.permission_request_grant(id,tenant_id,permission_request_id,target_user_id,target_identity_id,requested_role_id,user_role_id,"
                + "grant_status,effective_start_at,effective_end_at,executed_by,executed_at,version_no,created_by,updated_by) values ('" + DLQ_GRANT + "','"
                + TENANT + "','" + DLQ_REQUEST + "','" + USER + "','" + IDENTITY + "','" + ROLE + "','" + USER_ROLE
                + "','ACTIVE',now()-interval '5 days',now()-interval '2 hours','" + EMPLOYEE + "',now()-interval '5 days',1,'" + EMPLOYEE + "','" + EMPLOYEE + "')");
    }

    private static long scalarLong(String database, String sql) throws SQLException {
        try (Connection connection = admin(database); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String scalarString(String database, String sql) throws SQLException {
        String value = scalarNullableString(database, sql);
        assertNotNull(value);
        return value;
    }

    private static String scalarNullableString(String database, String sql) throws SQLException {
        try (Connection connection = admin(database); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static boolean scalarBoolean(String database, String sql) throws SQLException {
        try (Connection connection = admin(database); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = admin("sjg_oms"); Statement statement = connection.createStatement()) {
            assertFalse(statement.execute(sql));
        }
    }

    private static void migrate(String database, String generatedFolder, String overlayFolder) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway").resolve(generatedFolder));
        if (overlayFolder != null) {
            locations.add("filesystem:" + repoRoot.resolve("technical-platform/database/flyway-overlays").resolve(overlayFolder));
        }
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations.toArray(String[]::new))
                .placeholders(Map.of(
                        "sjg_tenant_id", TENANT.toString(),
                        "sjg_tenant_code", "PHASE09_P002",
                        "sjg_tenant_name", "PHASE-09 P002 Expiry Tenant"))
                .cleanDisabled(true)
                .load();
        assertTrue(flyway.migrate().success);
        flyway.validate();
    }

    private static DriverManagerDataSource dataSource(String database, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(jdbcUrl(database));
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
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
