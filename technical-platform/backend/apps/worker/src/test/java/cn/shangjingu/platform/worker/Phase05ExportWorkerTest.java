package cn.shangjingu.platform.worker;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.audit.SensitiveExportService;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class Phase05ExportWorkerTest {
    @Test
    void generatorSelectionIsExactAndFailClosed() {
        SensitiveExportService.SensitiveExportGenerator generator =
                mock(SensitiveExportService.SensitiveExportGenerator.class);
        when(generator.exportType()).thenReturn("SYNTHETIC");
        Phase05ExportWorker worker = new Phase05ExportWorker(
                mock(TenantTransactionRunner.class),
                mock(JdbcTemplate.class),
                mock(SensitiveExportService.class),
                List.of(generator),
                mock(WorkerCriticalAuditService.class));
        assertSame(generator, worker.resolve("SYNTHETIC"));
        assertThrows(ProcessRejectedException.class, () -> worker.resolve("OTHER"));
    }

    @Test
    void duplicateGeneratorsAreRejected() {
        SensitiveExportService.SensitiveExportGenerator first =
                mock(SensitiveExportService.SensitiveExportGenerator.class);
        SensitiveExportService.SensitiveExportGenerator second =
                mock(SensitiveExportService.SensitiveExportGenerator.class);
        when(first.exportType()).thenReturn("SYNTHETIC");
        when(second.exportType()).thenReturn("SYNTHETIC");
        Phase05ExportWorker worker = new Phase05ExportWorker(
                mock(TenantTransactionRunner.class),
                mock(JdbcTemplate.class),
                mock(SensitiveExportService.class),
                List.of(first, second),
                mock(WorkerCriticalAuditService.class));
        assertThrows(ProcessRejectedException.class, () -> worker.resolve("SYNTHETIC"));
    }
}
