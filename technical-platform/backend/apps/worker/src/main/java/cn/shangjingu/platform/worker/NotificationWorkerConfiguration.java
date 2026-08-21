package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.notification.NotificationDeliveryHandler;
import cn.shangjingu.platform.notification.NotificationDeliveryProvider;
import cn.shangjingu.platform.notification.NotificationService;
import cn.shangjingu.platform.notification.NotificationTemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnNotWebApplication
@ConditionalOnProperty(prefix="platform.notification.worker",name="enabled",havingValue="true")
public class NotificationWorkerConfiguration {
    @Bean @ConditionalOnMissingBean NotificationTemplateRenderer notificationTemplateRenderer(){return new NotificationTemplateRenderer(new ObjectMapper());}
    @Bean @ConditionalOnMissingBean TransactionalOutboxService notificationOutboxService(JdbcTemplate jdbc){return new TransactionalOutboxService(jdbc);}
    @Bean @ConditionalOnMissingBean NotificationService notificationService(JdbcTemplate jdbc,TransactionalOutboxService outbox,NotificationTemplateRenderer renderer){return new NotificationService(jdbc,outbox,renderer);}
    @Bean NotificationDeliveryHandler notificationDeliveryHandler(JdbcTemplate jdbc,ObjectProvider<NotificationDeliveryProvider> providerProvider){List<NotificationDeliveryProvider> providers=providerProvider.orderedStream().toList();return new NotificationDeliveryHandler(jdbc,providers);}
    @Bean NotificationDueMessagePump notificationDueMessagePump(JdbcTemplate jdbc,TenantTransactionRunner transactions,NotificationService notifications,@Value("${platform.notification.worker.batch-size:32}") int batchSize){return new NotificationDueMessagePump(jdbc,transactions,notifications,batchSize);}
}
