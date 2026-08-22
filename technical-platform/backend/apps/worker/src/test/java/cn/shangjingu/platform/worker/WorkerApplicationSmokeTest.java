package cn.shangjingu.platform.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class WorkerApplicationSmokeTest {
    @Test
    void startsWithoutExternalInfrastructureWhenAutoConfigurationIsDisabledForSmoke() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(WorkerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                        "--platform.outbox.enabled=false",
                        "--platform.notification.worker.enabled=false",
                        "--platform.phase09.p002.enabled=false")) {
            assertThat(context.isActive()).isTrue();
        }
    }
}
