package cn.shangjingu.platform.workflow.phase11;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** P015 projection: mutable workflow metadata plus immutable point-ledger facts. */
public record PointLedgerView(
        UUID id,
        UUID tenantId,
        String businessNo,
        UUID workflowInstanceId,
        String workflowInstanceNo,
        String currentNodeCode,
        String status,
        int versionNo,
        String subject,
        String priority,
        String riskLevel,
        UUID ownerCenterId,
        UUID ownerEmployeeId,
        LocalDate businessDate,
        Instant factOccurredAt,
        Long pointsDelta,
        String pointType,
        String changeAction,
        UUID reversalOfId,
        Long currentBalance,
        JsonNode details) {

    public PointLedgerView metadataOnly() {
        return new PointLedgerView(
                id, tenantId, businessNo, workflowInstanceId, workflowInstanceNo,
                currentNodeCode, status, versionNo, subject, priority, riskLevel,
                null, null, businessDate, null, null, null, null, null, null,
                JsonNodeFactory.instance.objectNode());
    }

    public Phase11Record workflowRecord() {
        return new Phase11Record(
                id, tenantId, Phase11Process.P015.code(), businessNo,
                workflowInstanceId, workflowInstanceNo, currentNodeCode, status, versionNo,
                subject, null, priority, riskLevel, ownerCenterId, ownerEmployeeId,
                businessDate, factOccurredAt, null, null, null, null, null,
                details == null ? JsonNodeFactory.instance.objectNode() : details);
    }
}
