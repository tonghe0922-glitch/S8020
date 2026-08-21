package cn.shangjingu.platform.org.domain;

import java.time.LocalDate;
import java.util.UUID;

public record EmployeeRecord(
        UUID id,
        UUID tenantId,
        String employeeNo,
        String personName,
        String employmentStatus,
        LocalDate hireDate,
        LocalDate leaveDate,
        UUID primaryOrgId,
        UUID primaryPositionId) {
}
