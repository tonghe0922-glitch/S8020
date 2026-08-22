package cn.shangjingu.platform.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

public final class Phase03DatabaseMigrator {
    private static final Pattern ADMIN_LOGIN_NAME = Pattern.compile("[A-Za-z0-9._@-]{1,128}");
    private static final Pattern ADMIN_EMPLOYEE_NO = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern BCRYPT_COST_12 = Pattern.compile("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}");

    private Phase03DatabaseMigrator() {}

    public static void main(String[] args) {
        Path root = findRepoRoot();
        Map<String, String> env = requiredEnvironment();

        Flyway cluster = Flyway.configure()
                .dataSource(
                        env.get("SJG_BOOTSTRAP_DB_URL"),
                        env.get("SJG_BOOTSTRAP_DB_USERNAME"),
                        env.get("SJG_BOOTSTRAP_DB_PASSWORD"))
                .locations("filesystem:" + root.resolve("technical-platform/database/flyway/cluster"))
                .cleanDisabled(true)
                .load();
        migrateAndValidate("cluster", cluster);

        migrateTarget(root, env, "sjg_oms", "oms", env.get("SJG_OMS_MIGRATION_DB_URL"));
        migrateTarget(root, env, "sjg_audit", "audit", env.get("SJG_AUDIT_MIGRATION_DB_URL"));
        migrateTarget(root, env, "sjg_dw", "dw", env.get("SJG_DW_MIGRATION_DB_URL"));

        System.out.println("PHASE-03 database migration completed for sjg_oms/sjg_audit/sjg_dw");
    }

    private static void migrateTarget(Path root, Map<String, String> env, String database, String folder, String url) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("sjg_tenant_id", sqlLiteral(env.get("SJG_TENANT_ID")));
        placeholders.put("sjg_tenant_code", sqlLiteral(env.get("SJG_TENANT_CODE")));
        placeholders.put("sjg_tenant_name", sqlLiteral(env.get("SJG_TENANT_NAME")));
        placeholders.put("sjg_admin_login_name", sqlLiteral(env.get("SJG_ADMIN_LOGIN_NAME")));
        placeholders.put("sjg_admin_password_hash", sqlLiteral(env.get("SJG_ADMIN_PASSWORD_HASH")));
        placeholders.put("sjg_admin_employee_no", sqlLiteral(env.get("SJG_ADMIN_EMPLOYEE_NO")));

        String initSql = migrationInitSql(folder, env);

        Flyway flyway = Flyway.configure()
                .dataSource(url, env.get("SJG_MIGRATION_DB_USERNAME"), env.get("SJG_MIGRATION_DB_PASSWORD"))
                .locations(
                        "filesystem:"
                                + root.resolve("technical-platform/database/flyway")
                                        .resolve(folder),
                        "filesystem:"
                                + root.resolve("technical-platform/database/flyway-overlays")
                                        .resolve(folder))
                .placeholders(placeholders)
                .initSql(initSql)
                .cleanDisabled(true)
                .load();
        migrateAndValidate(database, flyway);
    }

    static String migrationInitSql(String folder, Map<String, String> env) {
        if (!"oms".equals(folder)) {
            return "SET ROLE sjg_owner";
        }
        return "SET ROLE sjg_owner; DO $sjg_bootstrap$ BEGIN "
                + "PERFORM set_config('sjg.bootstrap.admin_login_name', '"
                + sqlLiteral(env.get("SJG_ADMIN_LOGIN_NAME"))
                + "', false); "
                + "PERFORM set_config('sjg.bootstrap.admin_password_hash', '"
                + sqlLiteral(env.get("SJG_ADMIN_PASSWORD_HASH"))
                + "', false); "
                + "PERFORM set_config('sjg.bootstrap.admin_employee_no', '"
                + sqlLiteral(env.get("SJG_ADMIN_EMPLOYEE_NO"))
                + "', false); END $sjg_bootstrap$";
    }

    private static void migrateAndValidate(String name, Flyway flyway) {
        MigrateResult first = flyway.migrate();
        if (!first.success) {
            throw new IllegalStateException("Flyway migrate failed for " + name);
        }
        flyway.validate();
        MigrateResult second = flyway.migrate();
        if (!second.success || second.migrationsExecuted != 0) {
            throw new IllegalStateException(
                    "Flyway repeatability failed for " + name + ": migrationsExecuted=" + second.migrationsExecuted);
        }
        System.out.println(
                "Flyway " + name + " migrate/validate/repeatability PASS; executed=" + first.migrationsExecuted);
    }

    static Map<String, String> requiredEnvironment() {
        String[] names = {
            "SJG_BOOTSTRAP_DB_URL",
            "SJG_BOOTSTRAP_DB_USERNAME",
            "SJG_BOOTSTRAP_DB_PASSWORD",
            "SJG_MIGRATION_DB_USERNAME",
            "SJG_MIGRATION_DB_PASSWORD",
            "SJG_OMS_MIGRATION_DB_URL",
            "SJG_AUDIT_MIGRATION_DB_URL",
            "SJG_DW_MIGRATION_DB_URL",
            "SJG_TENANT_ID",
            "SJG_TENANT_CODE",
            "SJG_TENANT_NAME",
            "SJG_ADMIN_LOGIN_NAME",
            "SJG_ADMIN_PASSWORD_HASH",
            "SJG_ADMIN_EMPLOYEE_NO"
        };
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : names) {
            String value = System.getenv(name);
            if (value == null || value.isBlank() || value.startsWith("__SET_LOCAL_")) {
                throw new IllegalStateException(
                        "Required deployment environment variable is missing or still a placeholder: " + name);
            }
            values.put(name, value);
        }
        validateEnvironment(values);
        return values;
    }

    private static void validateEnvironment(Map<String, String> values) {
        if (!"sjg_migration".equals(values.get("SJG_MIGRATION_DB_USERNAME"))) {
            throw new IllegalStateException("SJG_MIGRATION_DB_USERNAME must be sjg_migration");
        }
        if ("sjg_migration".equals(values.get("SJG_BOOTSTRAP_DB_USERNAME"))) {
            throw new IllegalStateException("bootstrap and migration identities must be separate");
        }
        try {
            UUID.fromString(values.get("SJG_TENANT_ID"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("SJG_TENANT_ID must be a valid UUID", exception);
        }
        if (!ADMIN_LOGIN_NAME.matcher(values.get("SJG_ADMIN_LOGIN_NAME")).matches()) {
            throw new IllegalStateException(
                    "SJG_ADMIN_LOGIN_NAME may contain only letters, digits, dot, underscore, @ and hyphen");
        }
        if (!ADMIN_EMPLOYEE_NO.matcher(values.get("SJG_ADMIN_EMPLOYEE_NO")).matches()) {
            throw new IllegalStateException(
                    "SJG_ADMIN_EMPLOYEE_NO may contain only letters, digits, dot, underscore and hyphen");
        }
        if (!BCRYPT_COST_12.matcher(values.get("SJG_ADMIN_PASSWORD_HASH")).matches()) {
            throw new IllegalStateException(
                    "SJG_ADMIN_PASSWORD_HASH must be a BCrypt cost=12 hash and must not be plaintext");
        }
    }

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (isRepoRoot(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found from " + System.getProperty("user.dir"));
    }

    static boolean isRepoRoot(Path candidate) {
        return candidate != null
                && Files.isRegularFile(candidate.resolve("AGENT.md"))
                && Files.isRegularFile(candidate.resolve("pom.xml"));
    }
}
