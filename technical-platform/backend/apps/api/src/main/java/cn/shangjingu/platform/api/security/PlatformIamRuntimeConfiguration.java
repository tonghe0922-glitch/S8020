package cn.shangjingu.platform.api.security;

import cn.shangjingu.platform.iam.application.IdentityDirectoryService;
import cn.shangjingu.platform.iam.session.RedisSessionStore;
import cn.shangjingu.platform.iam.session.SessionPolicy;
import cn.shangjingu.platform.iam.session.SessionService;
import cn.shangjingu.platform.iam.stepup.FailClosedMfaCapabilityProvider;
import cn.shangjingu.platform.iam.stepup.MfaCapabilityProvider;
import cn.shangjingu.platform.iam.stepup.RedisStepUpTicketStore;
import cn.shangjingu.platform.iam.stepup.StepUpPolicy;
import cn.shangjingu.platform.iam.stepup.StepUpService;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PlatformIamRuntimeConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    RedisSessionStore redisSessionStore(StringRedisTemplate redis) {
        return new RedisSessionStore(redis);
    }

    @Bean("sessionStore")
    HealthIndicator sessionStoreHealthIndicator(RedisSessionStore store) {
        return new SessionStoreHealthIndicator(store);
    }

    @Bean
    SessionService sessionService(
            IdentityDirectoryService identities,
            RedisSessionStore store,
            @Value("${sjg.security.session.access-ttl:PT15M}") String accessTtl,
            @Value("${sjg.security.session.refresh-ttl:P7D}") String refreshTtl) {
        return new SessionService(
                identities, store, new SessionPolicy(Duration.parse(accessTtl), Duration.parse(refreshTtl)));
    }

    @Bean
    RedisStepUpTicketStore redisStepUpTicketStore(StringRedisTemplate redis) {
        return new RedisStepUpTicketStore(redis);
    }

    @Bean
    @ConditionalOnMissingBean(MfaCapabilityProvider.class)
    MfaCapabilityProvider mfaCapabilityProvider() {
        return new FailClosedMfaCapabilityProvider();
    }

    @Bean
    JdbcSecurityAuditService securityAuditService(
            @Value("${sjg.audit.datasource.url:jdbc:postgresql://localhost:5432/sjg_audit}") String url,
            @Value("${sjg.audit.datasource.username:sjg_audit_writer}") String username,
            @Value("${sjg.audit.datasource.password:}") String password,
            @Value("${sjg.security.audit.mode:fail-open}") String mode) {
        return new JdbcSecurityAuditService(url, username, password, SecurityAuditMode.parse(mode));
    }

    @Bean("securityAudit")
    HealthIndicator securityAuditHealthIndicator(JdbcSecurityAuditService audit) {
        return new SecurityAuditHealthIndicator(audit);
    }

    @Bean
    StepUpService stepUpService(
            IdentityDirectoryService identities,
            RedisStepUpTicketStore store,
            MfaCapabilityProvider mfa,
            JdbcSecurityAuditService audit,
            @Value("${sjg.security.step-up.ticket-ttl:PT5M}") String ticketTtl) {
        return new StepUpService(identities, store, new StepUpPolicy(Duration.parse(ticketTtl)), mfa, audit);
    }
}
