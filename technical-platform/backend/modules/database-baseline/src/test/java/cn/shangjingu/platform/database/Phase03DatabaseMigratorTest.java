package cn.shangjingu.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase03DatabaseMigratorTest {
    @TempDir Path tempDir;

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
}
