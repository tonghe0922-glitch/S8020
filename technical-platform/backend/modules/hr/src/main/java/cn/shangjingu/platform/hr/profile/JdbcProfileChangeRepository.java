package cn.shangjingu.platform.hr.profile;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.hr.profile.ProfileChangeService.PreparedChange;
import cn.shangjingu.platform.hr.profile.ProfileChangeService.ProfileChange;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProfileChangeRepository implements ProfileChangeService.Repository {
    private final JdbcTemplate jdbc;
    private final ProfileChangeValueStore values;

    public JdbcProfileChangeRepository(JdbcTemplate jdbc, ProfileChangeValueStore values) {
        this.jdbc = jdbc;
        this.values = values;
    }

    @Override
    public Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode) {
        return jdbc.query("""
                select v.id from workflow.wf_version v
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where v.tenant_id=? and d.process_code=? and d.enabled and not d.is_deleted
                  and v.status='PUBLISHED' and not v.is_deleted
                  and (v.effective_at is null or v.effective_at<=now())
                order by v.version_no desc,v.effective_at desc nulls last,v.created_at desc limit 1
                """, (rs,n) -> rs.getObject(1, UUID.class), tenantId, processCode).stream().findFirst();
    }

    @Override
    public List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId,
                                           boolean sameOrg, UUID excludedEmployeeId) {
        String orgClause = sameOrg ? " and ui.org_id=? " : "";
        String sql = """
                select distinct ui.employee_id
                from iam.user_role ur
                join iam.role r on r.tenant_id=ur.tenant_id and r.id=ur.role_id and r.enabled and not r.is_deleted
                join iam.role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id and not rp.is_deleted
                join iam.permission p on p.tenant_id=rp.tenant_id and p.id=rp.permission_id and not p.is_deleted
                join iam.user_identity ui on ui.tenant_id=ur.tenant_id and ui.user_id=ur.user_id and not ui.is_deleted
                  and (ur.identity_id is null or ur.identity_id=ui.id)
                  and ui.effective_start_at<=now() and (ui.effective_end_at is null or ui.effective_end_at>now())
                join org.employee e on e.tenant_id=ui.tenant_id and e.id=ui.employee_id
                  and e.employment_status='ACTIVE' and not e.is_deleted
                where ur.tenant_id=? and p.permission_code=? and not ur.is_deleted
                  and ur.effective_start_at<=now() and (ur.effective_end_at is null or ur.effective_end_at>now())
                """ + orgClause + " and ui.employee_id<>? order by ui.employee_id";
        return sameOrg
                ? jdbc.query(sql, (rs,n) -> rs.getObject(1, UUID.class), tenantId, permissionCode, orgId, excludedEmployeeId)
                : jdbc.query(sql, (rs,n) -> rs.getObject(1, UUID.class), tenantId, permissionCode, excludedEmployeeId);
    }

    @Override
    public void insert(ProfileChange change, UUID actorId) {
        int inserted = jdbc.update("""
                insert into hr.employee_profile_change(
                    id,tenant_id,business_no,workflow_instance_id,status,version_no,created_by,updated_by,
                    source_channel,business_date,subject,reason,priority,risk_level,owner_center_id,owner_department_id,
                    owner_employee_id,change_action,change_reason,expected_effective_at,known_impact)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PROFILE_UPDATE',?,?,?)
                """, change.id(), change.tenantId(), change.businessNo(), null, change.status(), change.versionNo(),
                actorId, actorId, change.sourceChannel(), change.businessDate(), change.subject(), change.reason(),
                change.priority(), change.riskLevel(), change.ownerCenterId(), change.ownerDepartmentId(),
                change.ownerEmployeeId(), change.reason() == null ? "个人资料变更" : change.reason(),
                timestamp(change.expectedEffectiveAt()), change.knownImpact());
        if (inserted != 1) throw new ProcessRejectedException("P003 profile change insert failed");
    }

    @Override
    public void insertChanges(UUID tenantId, UUID requestId, UUID actorId, List<PreparedChange> changes) {
        values.insert(tenantId, requestId, actorId, changes);
    }

    @Override
    public int bindWorkflowAndMove(UUID tenantId, UUID id, int expectedVersion, UUID workflowInstanceId,
                                   String status, UUID actorId) {
        return jdbc.update("""
                update hr.employee_profile_change
                   set workflow_instance_id=?,status=?,version_no=version_no+1,updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and not is_deleted
                """, workflowInstanceId, status, actorId, tenantId, id, expectedVersion);
    }

    @Override
    public int moveStatus(UUID tenantId, UUID id, int expectedVersion, String status, String resultSummary,
                          Instant closedAt, UUID actorId) {
        return jdbc.update("""
                update hr.employee_profile_change
                   set status=?,result_summary=coalesce(?,result_summary),closed_at=coalesce(?,closed_at),
                       actual_start_at=case when actual_start_at is null and ?='关联模块投影同步' then now() else actual_start_at end,
                       actual_end_at=case when ?='已关闭' then coalesce(actual_end_at,now()) else actual_end_at end,
                       version_no=version_no+1,updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and not is_deleted
                """, status, resultSummary, timestamp(closedAt), status, status, actorId, tenantId, id, expectedVersion);
    }

    @Override
    public boolean approvedAtNode(UUID tenantId, UUID workflowInstanceId, String nodeCode, UUID employeeId) {
        Long count = jdbc.queryForObject("""
                select count(*) from workflow.wf_action_log
                 where tenant_id=? and instance_id=? and from_status=? and action_code='APPROVE'
                   and operator_id=? and not is_deleted
                """, Long.class, tenantId, workflowInstanceId, nodeCode, employeeId);
        return count != null && count > 0;
    }

    @Override
    public void applyAuthoritativeChanges(UUID tenantId, UUID requestId, UUID employeeId, UUID actorId) {
        values.apply(tenantId, requestId, employeeId, actorId);
    }

    @Override
    public Optional<ProfileChange> find(UUID tenantId, UUID id) {
        return jdbc.query(select("where tenant_id=? and id=? and not is_deleted"),
                (rs,n) -> map(rs), tenantId, id).stream().findFirst();
    }

    @Override
    public List<ProfileChange> list(UUID tenantId) {
        return jdbc.query(select("where tenant_id=? and not is_deleted order by created_at desc,id desc"),
                (rs,n) -> map(rs), tenantId);
    }

    private ProfileChange map(ResultSet rs) throws SQLException {
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        UUID id = rs.getObject("id", UUID.class);
        return new ProfileChange(id, tenantId, rs.getString("business_no"),
                rs.getObject("workflow_instance_id", UUID.class), rs.getString("status"), rs.getInt("version_no"),
                rs.getString("source_channel"), localDate(rs, "business_date"), rs.getString("subject"),
                rs.getString("reason"), rs.getString("priority"), rs.getString("risk_level"),
                rs.getObject("owner_center_id", UUID.class), rs.getObject("owner_department_id", UUID.class),
                rs.getObject("owner_employee_id", UUID.class), instant(rs, "expected_effective_at"),
                rs.getString("known_impact"), rs.getString("result_summary"), instant(rs, "closed_at"),
                instant(rs, "updated_at"), values.maskedViews(tenantId, id));
    }

    private String select(String suffix) {
        return """
                select id,tenant_id,business_no,workflow_instance_id,status,version_no,source_channel,business_date,
                       subject,reason,priority,risk_level,owner_center_id,owner_department_id,owner_employee_id,
                       expected_effective_at,known_impact,result_summary,closed_at,updated_at
                  from hr.employee_profile_change
                """ + suffix;
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String field) throws SQLException {
        Object value = rs.getObject(field);
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        throw new SQLException("unsupported timestamp type for " + field);
    }
    private static LocalDate localDate(ResultSet rs, String field) throws SQLException {
        java.sql.Date value = rs.getDate(field);
        return value == null ? null : value.toLocalDate();
    }
}
