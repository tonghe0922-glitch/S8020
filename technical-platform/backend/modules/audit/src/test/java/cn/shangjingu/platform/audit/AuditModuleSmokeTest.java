package cn.shangjingu.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class AuditModuleSmokeTest { @Test void exposesModuleIdentity() { assertThat(AuditModule.NAME).isEqualTo("audit"); } }
