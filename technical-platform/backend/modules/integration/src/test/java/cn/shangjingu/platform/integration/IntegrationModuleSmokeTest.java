package cn.shangjingu.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationModuleSmokeTest {
    @Test
    void exposesModuleIdentity() {
        assertThat(IntegrationModule.NAME).isEqualTo("integration");
    }
}
