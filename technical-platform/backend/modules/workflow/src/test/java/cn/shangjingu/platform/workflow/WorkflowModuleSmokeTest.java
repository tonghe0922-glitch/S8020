package cn.shangjingu.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class WorkflowModuleSmokeTest { @Test void exposesModuleIdentity() { assertThat(WorkflowModule.NAME).isEqualTo("workflow"); } }
