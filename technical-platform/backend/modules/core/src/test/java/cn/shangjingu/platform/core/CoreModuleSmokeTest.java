package cn.shangjingu.platform.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoreModuleSmokeTest {
    @Test
    void exposesModuleIdentity() {
        assertThat(CoreModule.NAME).isEqualTo("core");
    }
}
