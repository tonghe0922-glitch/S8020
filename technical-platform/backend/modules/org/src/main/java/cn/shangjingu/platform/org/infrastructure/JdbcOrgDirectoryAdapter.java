package cn.shangjingu.platform.org.infrastructure;

import cn.shangjingu.platform.org.domain.AppointmentRecord;
import cn.shangjingu.platform.org.domain.EmployeeRecord;
import cn.shangjingu.platform.org.domain.OrgDirectoryPort;
import cn.shangjingu.platform.org.domain.OrganizationUnit;
import cn.shangjingu.platform.org.domain.PositionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrgDirectoryAdapter implements OrgDirectoryPort {
    private final JdbcTemplate jdbc;

    public JdbcOrgDirectoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EmployeeRecord> findEmployee(UUID tenantId, UUID employeeId) {
        return jdbc.query("""
                select id,tenant_id,employee_no,person_name,employment_status,hire_date,leave_date,
                       primary_org_id,primary_position_id
                from org.employee
                where tenant_id=? and id=? and not is_deleted
                """, (rs, n) -> employee(rs), tenantId, employeeId).stream().findFirst();
    }

    @Override
    public Optional<OrganizationUnit> findOrganization(UUID tenantId, UUID orgId) {
        return jdbc.query("""
                select id,tenant_id,org_code,org_name,org_type,parent_id,path::text,status
                from org.organization
                where tenant_id=? and id=? and not is_deleted
                """, (rs, n) -> organization(rs), tenantId, orgId).stream().findFirst();
    }

    @Override
    public Optional<PositionRecord> findPosition(UUID tenantId, UUID positionId) {
        return jdbc.query("""
                select id,tenant_id,position_code,position_name,org_id,grade_id,status
                from org.position
                where tenant_id=? and id=? and not is_deleted
                """, (rs, n) -> position(rs), tenantId, positionId).stream().findFirst();
    }

    @Override
    public List<AppointmentRecord> findActiveAppointments(UUID tenantId, UUID employeeId) {
        return jdbc.query("""
                select id,tenant_id,employee_id,position_id,org_id,is_primary,effective_start_date,effective_end_date,status
                from org.employee_position
                where tenant_id=? and employee_id=? and not is_deleted and status='ACTIVE'
                  and effective_start_date <= current_date
                  and (effective_end_date is null or effective_end_date >= current_date)
                order by is_primary desc,effective_start_date,id
                """, (rs, n) -> appointment(rs), tenantId, employeeId);
    }

    @Override
    public List<AppointmentRecord> findActiveAppointmentsByOrgAndPosition(UUID tenantId, UUID orgId, UUID positionId) {
        return jdbc.query("""
                select ep.id,ep.tenant_id,ep.employee_id,ep.position_id,ep.org_id,ep.is_primary,
                       ep.effective_start_date,ep.effective_end_date,ep.status
                from org.employee_position ep
                join org.employee e
                  on e.tenant_id=ep.tenant_id and e.id=ep.employee_id and not e.is_deleted
                where ep.tenant_id=? and ep.org_id=? and ep.position_id=?
                  and not ep.is_deleted and ep.status='ACTIVE'
                  and ep.effective_start_date <= current_date
                  and (ep.effective_end_date is null or ep.effective_end_date >= current_date)
                order by ep.is_primary desc,ep.effective_start_date,ep.id
                """, (rs, n) -> appointment(rs), tenantId, orgId, positionId);
    }

    @Override
    public boolean hasActiveAppointment(UUID tenantId, UUID employeeId, UUID orgId, UUID positionId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from org.employee_position
                where tenant_id=? and employee_id=? and org_id=? and position_id=?
                  and not is_deleted and status='ACTIVE'
                  and effective_start_date <= current_date
                  and (effective_end_date is null or effective_end_date >= current_date)
                """, Integer.class, tenantId, employeeId, orgId, positionId);
        return count != null && count > 0;
    }

    private static EmployeeRecord employee(ResultSet rs) throws SQLException {
        return new EmployeeRecord(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("employee_no"), rs.getString("person_name"), rs.getString("employment_status"),
                rs.getObject("hire_date", java.time.LocalDate.class), rs.getObject("leave_date", java.time.LocalDate.class),
                rs.getObject("primary_org_id", UUID.class), rs.getObject("primary_position_id", UUID.class));
    }

    private static OrganizationUnit organization(ResultSet rs) throws SQLException {
        return new OrganizationUnit(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("org_code"), rs.getString("org_name"), rs.getString("org_type"),
                rs.getObject("parent_id", UUID.class), rs.getString("path"), rs.getString("status"));
    }

    private static PositionRecord position(ResultSet rs) throws SQLException {
        return new PositionRecord(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("position_code"), rs.getString("position_name"),
                rs.getObject("org_id", UUID.class), rs.getObject("grade_id", UUID.class), rs.getString("status"));
    }

    private static AppointmentRecord appointment(ResultSet rs) throws SQLException {
        return new AppointmentRecord(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("employee_id", UUID.class), rs.getObject("position_id", UUID.class), rs.getObject("org_id", UUID.class),
                rs.getBoolean("is_primary"), rs.getObject("effective_start_date", java.time.LocalDate.class),
                rs.getObject("effective_end_date", java.time.LocalDate.class), rs.getString("status"));
    }
}
