package cn.shangjingu.platform.worker;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.integration.DataImportService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class Phase05ImportWorkerTest {
    @Test
    void selectsExactlyOneExecutorForImportType() {
        DataImportService.ImportExecutor executor = mock(DataImportService.ImportExecutor.class);
        when(executor.importType()).thenReturn("SYNTHETIC");
        Phase05ImportWorker worker = new Phase05ImportWorker(
                mock(TenantTransactionRunner.class),
                mock(JdbcTemplate.class),
                mock(DataImportService.class),
                List.of(executor),
                mock(WorkerCriticalAuditService.class));
        assertSame(executor, worker.resolveExecutor("SYNTHETIC"));
        assertThrows(ProcessRejectedException.class, () -> worker.resolveExecutor("OTHER"));
    }

    @Test
    void ambiguousExecutorsFailClosed() {
        DataImportService.ImportExecutor first = mock(DataImportService.ImportExecutor.class);
        DataImportService.ImportExecutor second = mock(DataImportService.ImportExecutor.class);
        when(first.importType()).thenReturn("SYNTHETIC");
        when(second.importType()).thenReturn("SYNTHETIC");
        Phase05ImportWorker worker = new Phase05ImportWorker(
                mock(TenantTransactionRunner.class),
                mock(JdbcTemplate.class),
                mock(DataImportService.class),
                List.of(first, second),
                mock(WorkerCriticalAuditService.class));
        assertThrows(ProcessRejectedException.class, () -> worker.resolveExecutor("SYNTHETIC"));
    }
}
