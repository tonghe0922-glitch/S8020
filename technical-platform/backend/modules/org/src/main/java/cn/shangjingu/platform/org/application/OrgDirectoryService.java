package cn.shangjingu.platform.org.application;

import cn.shangjingu.platform.org.domain.AppointmentRecord;
import cn.shangjingu.platform.org.domain.EmployeeRecord;
import cn.shangjingu.platform.org.domain.OrgDirectoryPort;
import cn.shangjingu.platform.org.domain.OrganizationUnit;
import cn.shangjingu.platform.org.domain.PositionRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class OrgDirectoryService {
    private final OrgDirectoryPort directory;

    public OrgDirectoryService(OrgDirectoryPort directory) {
        this.directory = directory;
    }

    public Optional<EmployeeRecord> findEmployee(UUID tenantId, UUID employeeId) {
        return directory.findEmployee(tenantId, employeeId);
    }

    public Optional<OrganizationUnit> findOrganization(UUID tenantId, UUID orgId) {
        return directory.findOrganization(tenantId, orgId);
    }

    public Optional<PositionRecord> findPosition(UUID tenantId, UUID positionId) {
        return directory.findPosition(tenantId, positionId);
    }

    public List<AppointmentRecord> activeAppointments(UUID tenantId, UUID employeeId) {
        return directory.findActiveAppointments(tenantId, employeeId);
    }

    public boolean hasActiveAppointment(UUID tenantId, UUID employeeId, UUID orgId, UUID positionId) {
        return directory.hasActiveAppointment(tenantId, employeeId, orgId, positionId);
    }
}
