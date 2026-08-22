package cn.shangjingu.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase03DatabaseMigratorTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsDirectoryWithoutAgentDiscipline() throws Exception {
        Files.createFile(tempDir.resolve("pom.xml"));

        assertThat(Phase03DatabaseMigrator.isRepoRoot(tempDir)).isFalse();
    }

    @Test
    void rejectsDirectoryWithoutMavenAggregator() throws Exception {
        Files.createFile(tempDir.resolve("AGENT.md"));

        assertThat(Phase03DatabaseMigrator.isRepoRoot(tempDir)).isFalse();
    }

    @Test
    void acceptsRepositoryWithoutRemovedKnowledgeBaseDirectory() throws Exception {
        Files.createFile(tempDir.resolve("AGENT.md"));
        Files.createFile(tempDir.resolve("pom.xml"));

        assertThat(Files.exists(tempDir.resolve("Knowledge Base"))).isFalse();
        assertThat(Phase03DatabaseMigrator.isRepoRoot(tempDir)).isTrue();
    }

    @Test
    void initializesBootstrapSettingsWithoutReturningSensitiveQueryResults() {
        String sql = Phase03DatabaseMigrator.migrationInitSql(
                "oms",
                Map.of(
                        "SJG_ADMIN_LOGIN_NAME", "admin",
                        "SJG_ADMIN_PASSWORD_HASH", "$2b$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0",
                        "SJG_ADMIN_EMPLOYEE_NO", "S8020-E001"));

        assertThat(sql)
                .contains("DO $sjg_bootstrap$")
                .contains("PERFORM set_config('sjg.bootstrap.admin_password_hash'")
                .doesNotContain("SELECT set_config");
    }
}
