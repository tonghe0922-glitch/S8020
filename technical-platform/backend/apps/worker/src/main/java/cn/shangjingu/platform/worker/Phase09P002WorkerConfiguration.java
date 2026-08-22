package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.audit.PlatformAuditWriter;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.notification.NotificationDeliveryProvider;
import cn.shangjingu.platform.notification.NotificationService;
import cn.shangjingu.platform.workflow.CoreWorkflowIdempotency;
import cn.shangjingu.platform.workflow.FailClosedTransitionConditionEvaluator;
import cn.shangjingu.platform.workflow.JdbcWorkflowRuntimeRepository;
import cn.shangjingu.platform.workflow.WorkflowSystemActionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnNotWebApplication
@ConditionalOnProperty(prefix = "platform.phase09.p002", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Phase09P002WorkerConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper phase09P002ObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    TenantTransactionRunner phase09P002TenantTransactions(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new TenantTransactionRunner(jdbc, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    TransactionalOutboxService phase09P002Outbox(JdbcTemplate jdbc) {
        return new TransactionalOutboxService(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    PlatformAuditWriter phase09P002AuditWriter(
            @Value("${sjg.audit.datasource.url:jdbc:postgresql://localhost:5432/sjg_audit}") String url,
            @Value("${sjg.audit.datasource.username:sjg_audit_writer}") String username,
            @Value("${sjg.audit.datasource.password:}") String password) {
        DriverManagerDataSource auditDataSource = new DriverManagerDataSource();
        auditDataSource.setDriverClassName("org.postgresql.Driver");
        auditDataSource.setUrl(url);
        auditDataSource.setUsername(username);
        auditDataSource.setPassword(password);
        return new PlatformAuditWriter(
                new JdbcTemplate(auditDataSource), new DataSourceTransactionManager(auditDataSource));
    }

    @Bean
    WorkflowSystemActionService phase09P002WorkflowSystemActions(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new WorkflowSystemActionService(
                new JdbcWorkflowRuntimeRepository(jdbc, mapper),
                new CoreWorkflowIdempotency(new IdempotencyRegistry(jdbc)),
                new FailClosedTransitionConditionEvaluator(),
                mapper);
    }

    @Bean
    Phase09P002ExpiryWorker phase09P002ExpiryWorker(
            TenantTransactionRunner transactions,
            JdbcTemplate jdbc,
            WorkflowSystemActionService systemActions,
            TransactionalOutboxService outbox,
            PlatformAuditWriter audit,
            ObjectMapper mapper,
            @Value("${platform.phase09.p002.expiry.max-attempts:5}") int maxAttempts,
            @Value("${platform.phase09.p002.expiry.base-backoff-ms:1000}") long baseBackoffMs,
            @Value("${platform.phase09.p002.expiry.max-backoff-ms:60000}") long maxBackoffMs) {
        return new Phase09P002ExpiryWorker(
                transactions,
                jdbc,
                systemActions,
                outbox,
                audit,
                mapper,
                maxAttempts,
                Duration.ofMillis(baseBackoffMs),
                Duration.ofMillis(maxBackoffMs));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "platform.phase09.p002.expiry",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    Phase09P002ExpiryPump phase09P002ExpiryPump(
            Phase09P002ExpiryWorker worker, @Value("${platform.phase09.p002.expiry.batch-size:32}") int batchSize) {
        return new Phase09P002ExpiryPump(worker, batchSize);
    }

    @Bean
    NotificationDeliveryProvider phase09InAppNotificationDeliveryProvider() {
        return new InAppNotificationDeliveryProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.notification.worker", name = "enabled", havingValue = "true")
    Phase09P002NotificationHandler phase09P002NotificationHandler(
            NotificationService notifications, JdbcTemplate jdbc, ObjectMapper mapper) {
        return new Phase09P002NotificationHandler(notifications, jdbc, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.notification.worker", name = "enabled", havingValue = "true")
    Phase09P003NotificationHandler phase09P003NotificationHandler(
            NotificationService notifications, JdbcTemplate jdbc, ObjectMapper mapper) {
        return new Phase09P003NotificationHandler(notifications, jdbc, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.notification.worker", name = "enabled", havingValue = "true")
    Phase09P004NotificationHandler phase09P004NotificationHandler(
            NotificationService notifications, JdbcTemplate jdbc, ObjectMapper mapper) {
        return new Phase09P004NotificationHandler(notifications, jdbc, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.notification.worker", name = "enabled", havingValue = "true")
    Phase09P005NotificationHandler phase09P005NotificationHandler(
            NotificationService notifications, JdbcTemplate jdbc, ObjectMapper mapper) {
        return new Phase09P005NotificationHandler(notifications, jdbc, mapper);
    }
}
