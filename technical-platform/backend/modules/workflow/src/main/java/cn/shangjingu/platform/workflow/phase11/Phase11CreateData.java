package cn.shangjingu.platform.workflow.phase11;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Internal normalized P011 create payload. */
public record Phase11CreateData(
        String subject,
        String reason,
        String priority,
        String riskLevel,
        UUID ownerCenterId,
        UUID ownerEmployeeId,
        LocalDate businessDate,
        Instant factOccurredAt,
        String factSummary,
        String contentVersion,
        String periodNo) {}
