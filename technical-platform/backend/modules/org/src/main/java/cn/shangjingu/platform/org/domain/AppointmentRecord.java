package cn.shangjingu.platform.org.domain;

import java.time.LocalDate;
import java.util.UUID;

public record AppointmentRecord(
        UUID id,
        UUID tenantId,
        UUID employeeId,
        UUID positionId,
        UUID orgId,
        boolean primary,
        LocalDate effectiveStartDate,
        LocalDate effectiveEndDate,
        String status) {
}
