package cn.shangjingu.platform.org.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgDirectoryPort {
    Optional<EmployeeRecord> findEmployee(UUID tenantId, UUID employeeId);

    Optional<OrganizationUnit> findOrganization(UUID tenantId, UUID orgId);

    Optional<PositionRecord> findPosition(UUID tenantId, UUID positionId);

    List<AppointmentRecord> findActiveAppointments(UUID tenantId, UUID employeeId);

    List<AppointmentRecord> findActiveAppointmentsByOrgAndPosition(UUID tenantId, UUID orgId, UUID positionId);

    boolean hasActiveAppointment(UUID tenantId, UUID employeeId, UUID orgId, UUID positionId);
}
