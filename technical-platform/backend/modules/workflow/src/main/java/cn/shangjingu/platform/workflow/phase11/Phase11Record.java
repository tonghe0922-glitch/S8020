package cn.shangjingu.platform.workflow.phase11;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Canonical projection shared by the three portals; details remain process-specific. */
public record Phase11Record(
        UUID id,
        UUID tenantId,
        String processCode,
        String businessNo,
        UUID workflowInstanceId,
        String workflowInstanceNo,
        String currentNodeCode,
        String status,
        int versionNo,
        String subject,
        String reason,
        String priority,
        String riskLevel,
        UUID ownerCenterId,
        UUID ownerEmployeeId,
        LocalDate businessDate,
        Instant factOccurredAt,
        String factSummary,
        String resultSummary,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        JsonNode details) {
    public Phase11Record metadataOnly() {
        return new Phase11Record(
                id,
                tenantId,
                processCode,
                businessNo,
                workflowInstanceId,
                workflowInstanceNo,
                currentNodeCode,
                status,
                versionNo,
                subject,
                null,
                priority,
                riskLevel,
                ownerCenterId,
                null,
                businessDate,
                null,
                null,
                null,
                createdAt,
                updatedAt,
                closedAt,
                JsonNodeFactory.instance.objectNode());
    }
}
