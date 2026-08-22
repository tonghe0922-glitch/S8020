package cn.shangjingu.platform.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class ApiApplicationSmokeTest {
    @Test
    void startsWithoutConnectingToExternalInfrastructureWhenBeansAreLazy() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ApiApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.lazy-initialization=true", "spring.flyway.enabled=false")
                .run()) {
            assertThat(context.isActive()).isTrue();
        }
    }
}
