package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.audit.JdbcSensitiveExportRepository;
import cn.shangjingu.platform.audit.SensitiveExportService;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnBean(JdbcTemplate.class)
@Import({
    TenantTransactionRunner.class,
    IdempotencyRegistry.class,
    BusinessNumberService.class,
    SensitiveExportService.class,
    JdbcSensitiveExportRepository.class
})
public class Phase05ExportWorkerConfiguration {
    @Bean
    Phase05ExportWorker phase05ExportWorker(
            TenantTransactionRunner transactions,
            JdbcTemplate jdbc,
            SensitiveExportService exports,
            List<SensitiveExportService.SensitiveExportGenerator> generators,
            WorkerCriticalAuditService audit) {
        return new Phase05ExportWorker(transactions, jdbc, exports, generators, audit);
    }
}
