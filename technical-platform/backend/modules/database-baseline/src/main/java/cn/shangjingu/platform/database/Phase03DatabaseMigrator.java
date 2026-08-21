package cn.shangjingu.platform.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

public final class Phase03DatabaseMigrator {

    private Phase03DatabaseMigrator() {}

    public static void main(String[] args) {
        Path root = findRepoRoot();
        Map<String, String> env = requiredEnvironment();

        Flyway cluster = Flyway.configure()
                .dataSource(env.get("SJG_BOOTSTRAP_DB_URL"), env.get("SJG_BOOTSTRAP_DB_USERNAME"), env.get("SJG_BOOTSTRAP_DB_PASSWORD"))
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
        Flyway flyway = Flyway.configure()
                .dataSource(url, env.get("SJG_MIGRATION_DB_USERNAME"), env.get("SJG_MIGRATION_DB_PASSWORD"))
                .locations(
                        "filesystem:" + root.resolve("technical-platform/database/flyway").resolve(folder),
                        "filesystem:" + root.resolve("technical-platform/database/flyway-overlays").resolve(folder))
                .placeholders(Map.of(
                        "sjg_tenant_id", env.get("SJG_TENANT_ID"),
                        "sjg_tenant_code", env.get("SJG_TENANT_CODE"),
                        "sjg_tenant_name", env.get("SJG_TENANT_NAME")))
                .initSql("SET ROLE sjg_owner")
                .cleanDisabled(true)
                .load();
        migrateAndValidate(database, flyway);
    }

    private static void migrateAndValidate(String name, Flyway flyway) {
        MigrateResult first = flyway.migrate();
        if (!first.success) {
            throw new IllegalStateException("Flyway migrate failed for " + name);
        }
        flyway.validate();
        MigrateResult second = flyway.migrate();
        if (!second.success || second.migrationsExecuted != 0) {
            throw new IllegalStateException("Flyway repeatability failed for " + name + ": migrationsExecuted=" + second.migrationsExecuted);
        }
        System.out.println("Flyway " + name + " migrate/validate/repeatability PASS; executed=" + first.migrationsExecuted);
    }

    private static Map<String, String> requiredEnvironment() {
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
                "SJG_TENANT_NAME"
        };
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : names) {
            String value = System.getenv(name);
            if (value == null || value.isBlank() || value.startsWith("__SET_LOCAL_")) {
                throw new IllegalStateException("Required deployment environment variable is missing or still a placeholder: " + name);
            }
            values.put(name, value);
        }
        if (!"sjg_migration".equals(values.get("SJG_MIGRATION_DB_USERNAME"))) {
            throw new IllegalStateException("SJG_MIGRATION_DB_USERNAME must be sjg_migration");
        }
        if ("sjg_migration".equals(values.get("SJG_BOOTSTRAP_DB_USERNAME"))) {
            throw new IllegalStateException("bootstrap and migration identities must be separate");
        }
        return values;
    }

    private static Path findRepoRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("AGENT.md"))
                    && Files.isDirectory(current.resolve("Knowledge Base"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found from " + System.getProperty("user.dir"));
    }
}
