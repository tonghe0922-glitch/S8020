package cn.shangjingu.platform.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentModuleSmokeTest {
    @Test
    void exposesModuleIdentity() {
        assertThat(DocumentModule.NAME).isEqualTo("document");
    }
}
