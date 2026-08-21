package cn.shangjingu.platform.api.platform;

import cn.shangjingu.platform.core.event.TransactionalOutboxService;
import cn.shangjingu.platform.integration.WebhookIngressService;
import cn.shangjingu.platform.integration.WebhookSignatureVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
public class PlatformWebhookRuntimeConfiguration {
    @Bean @ConditionalOnMissingBean
    WebhookIngressService platformWebhookIngressService(JdbcTemplate jdbc,TransactionalOutboxService outbox){return new WebhookIngressService(jdbc,outbox);}
    @Bean @ConditionalOnMissingBean(WebhookSignatureVerifier.class)
    WebhookSignatureVerifier failClosedWebhookSignatureVerifier(){return request->false;}
}
