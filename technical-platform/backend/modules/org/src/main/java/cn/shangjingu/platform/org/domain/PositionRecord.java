package cn.shangjingu.platform.org.domain;

import java.util.UUID;

public record PositionRecord(
        UUID id,
        UUID tenantId,
        String positionCode,
        String positionName,
        UUID orgId,
        UUID gradeId,
        String status) {
}
