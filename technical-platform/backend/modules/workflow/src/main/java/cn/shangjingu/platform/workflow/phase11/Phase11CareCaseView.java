package cn.shangjingu.platform.workflow.phase11;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read model over the canonical welfare.care_case plus additive P016 supporting facts. */
public record Phase11CareCaseView(
        UUID id,
        UUID tenantId,
        String processCode,
        String businessNo,
        UUID workflowInstanceId,
        String currentNodeCode,
        String status,
        int versionNo,
        String subject,
        String reason,
        String priority,
        String riskLevel,
        UUID ownerCenterId,
        UUID ownerDepartmentId,
        UUID ownerEmployeeId,
        LocalDate businessDate,
        BigDecimal benefitAmount,
        String budgetItemId,
        String costCenterId,
        String currency,
        Instant factOccurredAt,
        String factSummary,
        String impactLevel,
        String resultSummary,
        Instant actualStartAt,
        Instant actualEndAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt,
        List<FactView> facts) {

    public Phase11CareCaseView {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public Phase11CareCaseView metadataOnly() {
        return new Phase11CareCaseView(
                id,
                tenantId,
                processCode,
                businessNo,
                workflowInstanceId,
                currentNodeCode,
                status,
                versionNo,
                null,
                null,
                null,
                riskLevel,
                null,
                null,
                null,
                businessDate,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                actualStartAt,
                actualEndAt,
                closedAt,
                createdAt,
                updatedAt,
                List.of());
    }

    public record FactView(
            UUID id,
            String factType,
            String summary,
            String evidenceReference,
            UUID actorEmployeeId,
            Instant occurredAt) {}
}
