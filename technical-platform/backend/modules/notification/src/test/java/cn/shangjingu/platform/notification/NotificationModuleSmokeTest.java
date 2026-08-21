package cn.shangjingu.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class NotificationModuleSmokeTest { @Test void exposesModuleIdentity() { assertThat(NotificationModule.NAME).isEqualTo("notification"); } }
