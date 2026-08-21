package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.BusinessNumberService;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class CoreWorkflowOrchestrationNumberCapability implements WorkflowOrchestrationService.OrchestrationNumberCapability {
    private final BusinessNumberService numbers;

    public CoreWorkflowOrchestrationNumberCapability(BusinessNumberService numbers) {
        this.numbers = numbers;
    }

    @Override
    public String next(UUID tenantId, UUID actorId, String processCode) {
        return numbers.next(tenantId, actorId, processCode);
    }
}
