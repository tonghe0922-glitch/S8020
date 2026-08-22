package cn.shangjingu.platform.iam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IamModuleSmokeTest {
    @Test
    void exposesModuleIdentity() {
        assertThat(IamModule.NAME).isEqualTo("iam");
    }
}
