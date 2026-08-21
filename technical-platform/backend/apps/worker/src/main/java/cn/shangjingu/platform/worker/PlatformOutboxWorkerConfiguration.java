package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.PlatformInboxService;
import cn.shangjingu.platform.core.event.PlatformOutboxHandler;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnNotWebApplication
@ConditionalOnProperty(prefix="platform.outbox",name="enabled",havingValue="true")
public class PlatformOutboxWorkerConfiguration {
    @Bean @ConditionalOnMissingBean TenantTransactionRunner platformTenantTransactions(JdbcTemplate jdbcTemplate,PlatformTransactionManager transactionManager){return new TenantTransactionRunner(jdbcTemplate,transactionManager);}
    @Bean @ConditionalOnMissingBean PlatformInboxService platformInboxService(JdbcTemplate jdbc){return new PlatformInboxService(jdbc);}
    @Bean PlatformOutboxWorker platformOutboxWorker(TenantTransactionRunner transactions,JdbcTemplate jdbc,PlatformInboxService inbox,ObjectProvider<PlatformOutboxHandler> handlerProvider,@Value("${platform.outbox.max-attempts:8}") int maxAttempts,@Value("${platform.outbox.base-backoff-ms:1000}") long baseBackoffMs,@Value("${platform.outbox.max-backoff-ms:300000}") long maxBackoffMs){List<PlatformOutboxHandler> handlers=handlerProvider.orderedStream().toList();return new PlatformOutboxWorker(transactions,jdbc,inbox,handlers,maxAttempts,Duration.ofMillis(baseBackoffMs),Duration.ofMillis(maxBackoffMs));}
    @Bean PlatformOutboxPump platformOutboxPump(PlatformOutboxWorker worker,@Value("${platform.outbox.batch-size:32}") int batchSize){return new PlatformOutboxPump(worker,batchSize);}
}
