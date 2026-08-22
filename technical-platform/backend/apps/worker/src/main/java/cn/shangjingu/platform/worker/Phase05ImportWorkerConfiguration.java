package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.integration.DataImportService;
import cn.shangjingu.platform.integration.JdbcDataImportRepository;
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
    DataImportService.class,
    JdbcDataImportRepository.class
})
public class Phase05ImportWorkerConfiguration {
    @Bean
    Phase05ImportWorker phase05ImportWorker(
            TenantTransactionRunner transactions,
            JdbcTemplate jdbc,
            DataImportService imports,
            List<DataImportService.ImportExecutor> executors,
            WorkerCriticalAuditService audit) {
        return new Phase05ImportWorker(transactions, jdbc, imports, executors, audit);
    }
}
