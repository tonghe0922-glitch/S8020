package cn.shangjingu.platform.org;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrgModuleSmokeTest {
    @Test
    void exposesModuleIdentity() {
        assertThat(OrgModule.NAME).isEqualTo("org");
    }
}
