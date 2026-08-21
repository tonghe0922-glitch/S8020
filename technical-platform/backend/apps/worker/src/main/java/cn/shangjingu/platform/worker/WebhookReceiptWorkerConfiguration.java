package cn.shangjingu.platform.worker;

import cn.shangjingu.platform.integration.WebhookProcessingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnNotWebApplication
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(prefix="platform.outbox",name="enabled",havingValue="true")
public class WebhookReceiptWorkerConfiguration {
    @Bean WebhookProcessingService webhookProcessingService(JdbcTemplate jdbc){return new WebhookProcessingService(jdbc);}
    @Bean WebhookReceiptHandler webhookReceiptHandler(WebhookProcessingService processing){return new WebhookReceiptHandler(processing);}
}
