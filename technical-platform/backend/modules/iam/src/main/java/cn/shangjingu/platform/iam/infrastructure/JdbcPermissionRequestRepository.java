package cn.shangjingu.platform.iam.infrastructure;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.iam.application.PermissionRequestService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class JdbcPermissionRequestRepository implements PermissionRequestService.Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcPermissionRequestRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<PermissionRequestService.EmployeeSnapshot> employee(UUID tenantId, UUID employeeId) {
        return jdbc.query("""
                select employee_no,person_name from org.employee
                where tenant_id=? and id=? and employment_status='ACTIVE' and not is_deleted
                """, (rs,n) -> new PermissionRequestService.EmployeeSnapshot(rs.getString("employee_no"), rs.getString("person_name")),
                tenantId, employeeId).stream().findFirst();
    }

    @Override
    public void requireRole(UUID tenantId, UUID roleId) {
        Integer found = jdbc.query("select 1 from iam.role where tenant_id=? and id=? and enabled and not is_deleted",
                rs -> rs.next() ? 1 : null, tenantId, roleId);
        if (found == null) throw new ProcessRejectedException("P002 requested role does not exist or is disabled");
    }

    /**
     * Returns the authoritative risk for a role from the permissions actually attached to it.
     * The requester never supplies this decision input. Missing permissions and unknown risk
     * labels fail closed so that S05 cannot be bypassed by malformed IAM data.
     */
    public String roleRisk(UUID tenantId, UUID roleId) {
        requireRole(tenantId, roleId);
        List<String> risks = jdbc.query("""
                select p.risk_level
                  from iam.role_permission rp
                  join iam.permission p
                    on p.tenant_id=rp.tenant_id and p.id=rp.permission_id and not p.is_deleted
                 where rp.tenant_id=? and rp.role_id=? and not rp.is_deleted
                 order by p.id
                """, (rs,n) -> rs.getString(1), tenantId, roleId);
        if (risks.isEmpty()) {
            throw new ProcessRejectedException("P002 requested role has no effective permissions");
        }
        int highest = -1;
        String result = null;
        for (String raw : risks) {
            String risk = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
            int rank = switch (risk) {
                case "LOW" -> 0;
                case "NORMAL" -> 1;
                case "MEDIUM" -> 2;
                case "HIGH" -> 3;
                case "CRITICAL" -> 4;
                default -> throw new ProcessRejectedException("P002 requested role contains an unknown permission risk level");
            };
            if (rank > highest) {
                highest = rank;
                result = risk;
            }
        }
        if (result == null) {
            throw new ProcessRejectedException("P002 requested role risk cannot be determined");
        }
        return result;
    }

    @Override
    public boolean hasOverlappingGrant(UUID tenantId, UUID userId, UUID identityId, UUID roleId, Instant start, Instant end) {
        Long count = jdbc.queryForObject("""
                select count(*) from iam.user_role
                where tenant_id=? and user_id=? and role_id=? and not is_deleted
                  and (identity_id is null or identity_id=?)
                  and effective_start_at < ?
                  and (effective_end_at is null or effective_end_at > ?)
                """, Long.class, tenantId, userId, roleId, identityId, timestamp(end), timestamp(start));
        return count != null && count > 0;
    }

    @Override
    public Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode) {
        return jdbc.query("""
                select v.id
                from workflow.wf_version v
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where v.tenant_id=? and d.process_code=? and d.enabled and not d.is_deleted
                  and v.status='PUBLISHED' and not v.is_deleted
                  and (v.effective_at is null or v.effective_at <= now())
                order by v.version_no desc,v.effective_at desc nulls last,v.created_at desc
                limit 1
                """, (rs,n) -> rs.getObject(1, UUID.class), tenantId, processCode).stream().findFirst();
    }

    @Override
    public List<UUID> permissionCandidates(
            UUID tenantId, String permissionCode, UUID orgId, boolean sameOrg, UUID excludedEmployeeId) {
        String orgClause = sameOrg ? " and ui.org_id=? " : "";
        String sql = """
                select distinct ui.employee_id
                from iam.user_role ur
                join iam.role r on r.tenant_id=ur.tenant_id and r.id=ur.role_id and r.enabled and not r.is_deleted
                join iam.role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id and not rp.is_deleted
                join iam.permission p on p.tenant_id=rp.tenant_id and p.id=rp.permission_id and not p.is_deleted
                join iam.user_identity ui on ui.tenant_id=ur.tenant_id and ui.user_id=ur.user_id and not ui.is_deleted
                  and (ur.identity_id is null or ur.identity_id=ui.id)
                  and ui.effective_start_at <= now() and (ui.effective_end_at is null or ui.effective_end_at > now())
                join org.employee e on e.tenant_id=ui.tenant_id and e.id=ui.employee_id
                  and e.employment_status='ACTIVE' and not e.is_deleted
                where ur.tenant_id=? and p.permission_code=? and not ur.is_deleted
                  and ur.effective_start_at <= now() and (ur.effective_end_at is null or ur.effective_end_at > now())
                """ + orgClause + " and ui.employee_id<>? order by ui.employee_id";
        return sameOrg
                ? jdbc.query(sql, (rs,n) -> rs.getObject(1, UUID.class), tenantId, permissionCode, orgId, excludedEmployeeId)
                : jdbc.query(sql, (rs,n) -> rs.getObject(1, UUID.class), tenantId, permissionCode, excludedEmployeeId);
    }

    private String positionCode(UUID tenantId, UUID positionId) {
        String code = jdbc.query("""
                select position_code from org.position
                where tenant_id=? and id=? and status='ACTIVE' and not is_deleted
                """, rs -> rs.next() ? rs.getString(1) : null, tenantId, positionId);
        if (code == null || code.isBlank()) {
            throw new ProcessRejectedException("P002 current position does not exist or is inactive");
        }
        return code;
    }

    @Override
    public void insert(PermissionRequestService.PermissionRequest request, PermissionRequestService.EmployeeSnapshot employee,
                       UUID positionId, UUID actorId) {
        int inserted = jdbc.update("""
                insert into iam.permission_request(
                    id,tenant_id,business_no,workflow_instance_id,status,version_no,created_by,updated_by,
                    source_channel,business_date,subject,reason,priority,risk_level,owner_center_id,owner_department_id,
                    owner_employee_id,planned_start_at,planned_finish_at,employee_event_type,employment_type,
                    person_name,person_no,planned_effective_date,target_job_id)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, request.id(), request.tenantId(), request.businessNo(), null, request.status(), request.versionNo(),
                actorId, actorId, request.sourceChannel(), request.businessDate(), request.subject(), request.reason(),
                request.priority(), request.riskLevel(), request.ownerCenterId(), request.ownerDepartmentId(),
                request.ownerEmployeeId(), timestamp(request.plannedStartAt()), timestamp(request.plannedFinishAt()),
                "PERMISSION_REQUEST", "CURRENT_APPOINTMENT", employee.personName(), employee.employeeNo(),
                request.plannedEffectiveDate(), positionCode(request.tenantId(), positionId));
        if (inserted != 1) throw new ProcessRejectedException("P002 permission request insert failed");
        inserted = jdbc.update("""
                insert into iam.permission_request_grant(
                    id,tenant_id,permission_request_id,target_user_id,target_identity_id,requested_role_id,grant_status,
                    effective_start_at,effective_end_at,version_no,created_by,updated_by)
                values (?,?,?,?,?,?,'REQUESTED',?,?,0,?,?)
                """, UUID.randomUUID(), request.tenantId(), request.id(), request.targetUserId(), request.targetIdentityId(),
                request.requestedRoleId(), timestamp(request.effectiveStartAt()), timestamp(request.effectiveEndAt()), actorId, actorId);
        if (inserted != 1) throw new ProcessRejectedException("P002 permission request grant linkage insert failed");
    }

    @Override
    public void insertItems(UUID tenantId, UUID requestId, UUID actorId, PermissionRequestService.CreateCommand command) {
        int seq = 0;
        seq = textItem(tenantId, requestId, actorId, seq, "business_object_type", command.businessObjectType());
        seq = textItem(tenantId, requestId, actorId, seq, "business_object_no", command.businessObjectNo());
        seq = textItem(tenantId, requestId, actorId, seq, "business_object_name", command.businessObjectName());
        seq = textItem(tenantId, requestId, actorId, seq, "business_scope_id", command.businessScopeId());
        seq = textItem(tenantId, requestId, actorId, seq, "external_reference_no", command.externalReferenceNo());
        seq = textItem(tenantId, requestId, actorId, seq, "expected_result", command.expectedResult());
        if (command.attachments() != null && !command.attachments().isNull()) {
            jsonItem(tenantId, requestId, actorId, seq, "attachments", command.attachments());
        }
    }

    private int textItem(UUID tenantId, UUID requestId, UUID actorId, int seq, String field, String value) {
        if (value == null || value.isBlank()) return seq;
        jdbc.update("""
                insert into iam.permission_request_item(
                    id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_value_text,sort_no)
                values (?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, actorId, actorId, requestId, field, seq, value.trim(), seq);
        return seq + 1;
    }

    private void jsonItem(UUID tenantId, UUID requestId, UUID actorId, int seq, String field, JsonNode value) {
        jdbc.update("""
                insert into iam.permission_request_item(
                    id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_value_json,sort_no)
                values (?,?,?,?,?,?,?,cast(? as jsonb),?)
                """, UUID.randomUUID(), tenantId, actorId, actorId, requestId, field, seq, json(value), seq);
    }

    @Override
    public int bindWorkflowAndMove(UUID tenantId, UUID id, int expectedVersion, UUID workflowInstanceId, String status, UUID actorId) {
        return jdbc.update("""
                update iam.permission_request
                   set workflow_instance_id=?,status=?,version_no=version_no+1,updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and not is_deleted
                """, workflowInstanceId, status, actorId, tenantId, id, expectedVersion);
    }

    @Override
    public int moveStatus(UUID tenantId, UUID id, int expectedVersion, String status, String resultSummary,
                          Instant closedAt, UUID actorId) {
        return jdbc.update("""
                update iam.permission_request
                   set status=?,result_summary=coalesce(?,result_summary),closed_at=coalesce(?,closed_at),
                       version_no=version_no+1,updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and not is_deleted
                """, status, resultSummary, timestamp(closedAt), actorId, tenantId, id, expectedVersion);
    }

    @Override
    public UUID activateGrant(UUID tenantId, UUID requestId, UUID userId, UUID identityId, UUID roleId,
                              Instant start, Instant end, UUID actorId) {
        GrantLink link = lockGrant(tenantId, requestId);
        if ("ACTIVE".equals(link.status()) && link.userRoleId() != null) return link.userRoleId();
        if (!"REQUESTED".equals(link.status())) throw new ProcessRejectedException("P002 grant is not executable");
        UUID userRoleId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into iam.user_role(
                    id,tenant_id,created_by,updated_by,user_id,identity_id,role_id,effective_start_at,effective_end_at,grant_source)
                values (?,?,?,?,?,?,?,?,?,'P002_PERMISSION_REQUEST')
                """, userRoleId, tenantId, actorId, actorId, userId, identityId, roleId, timestamp(start), timestamp(end));
        if (inserted != 1) throw new ProcessRejectedException("P002 user role grant insert failed");
        int changed = jdbc.update("""
                update iam.permission_request_grant
                   set user_role_id=?,grant_status='ACTIVE',executed_by=?,executed_at=now(),version_no=version_no+1,
                       updated_by=?,updated_at=now()
                 where tenant_id=? and permission_request_id=? and grant_status='REQUESTED'
                """, userRoleId, actorId, actorId, tenantId, requestId);
        if (changed != 1) throw new ProcessRejectedException("P002 grant linkage changed concurrently");
        return userRoleId;
    }

    @Override
    public int markExecutedAndMove(UUID tenantId, UUID id, int expectedVersion, UUID userRoleId, String status, UUID actorId) {
        return jdbc.update("""
                update iam.permission_request p
                   set status=?,actual_start_at=coalesce(actual_start_at,now()),actual_effective_date=current_date,
                       version_no=version_no+1,updated_by=?,updated_at=now()
                 where p.tenant_id=? and p.id=? and p.version_no=? and not p.is_deleted
                   and exists (select 1 from iam.permission_request_grant g
                               where g.tenant_id=p.tenant_id and g.permission_request_id=p.id
                                 and g.user_role_id=? and g.grant_status='ACTIVE')
                """, status, actorId, tenantId, id, expectedVersion, userRoleId);
    }

    @Override
    public void revokeGrant(UUID tenantId, UUID requestId, UUID actorId, String reason, Instant revokedAt) {
        GrantLink link = lockGrant(tenantId, requestId);
        if ("REVOKED".equals(link.status())) return;
        if (!"ACTIVE".equals(link.status()) || link.userRoleId() == null)
            throw new ProcessRejectedException("P002 active grant not found for revoke");
        jdbc.update("""
                update iam.user_role
                   set effective_end_at=case when effective_end_at is null or effective_end_at>? then ? else effective_end_at end,
                       updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and not is_deleted
                """, timestamp(revokedAt), timestamp(revokedAt), actorId, tenantId, link.userRoleId());
        int changed = jdbc.update("""
                update iam.permission_request_grant
                   set grant_status='REVOKED',revoked_by=?,revoked_at=?,revoke_reason=?,version_no=version_no+1,
                       updated_by=?,updated_at=now()
                 where tenant_id=? and permission_request_id=? and grant_status='ACTIVE'
                """, actorId, timestamp(revokedAt), reason, actorId, tenantId, requestId);
        if (changed != 1) throw new ProcessRejectedException("P002 grant revoke changed concurrently");
    }

    @Override
    public int markRevokedAndClose(UUID tenantId, UUID id, int expectedVersion, String status, String resultSummary,
                                   Instant closedAt, UUID actorId) {
        return jdbc.update("""
                update iam.permission_request
                   set status=?,result_summary=coalesce(?,result_summary),actual_end_at=coalesce(?,now()),
                       closed_at=coalesce(?,now()),version_no=version_no+1,updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and not is_deleted
                   and exists (select 1 from iam.permission_request_grant g
                               where g.tenant_id=iam.permission_request.tenant_id
                                 and g.permission_request_id=iam.permission_request.id and g.grant_status='REVOKED')
                """, status, resultSummary, timestamp(closedAt), timestamp(closedAt), actorId, tenantId, id, expectedVersion);
    }

    @Override
    public Optional<PermissionRequestService.PermissionRequest> find(UUID tenantId, UUID id) {
        return jdbc.query(select("where p.tenant_id=? and p.id=? and not p.is_deleted"),
                (rs,n) -> map(rs), tenantId, id).stream().findFirst();
    }

    @Override
    public List<PermissionRequestService.PermissionRequest> list(UUID tenantId) {
        return jdbc.query(select("where p.tenant_id=? and not p.is_deleted order by p.created_at desc,p.id desc"),
                (rs,n) -> map(rs), tenantId);
    }

    private GrantLink lockGrant(UUID tenantId, UUID requestId) {
        GrantLink link = jdbc.query("""
                select grant_status,user_role_id from iam.permission_request_grant
                where tenant_id=? and permission_request_id=? for update
                """, rs -> rs.next() ? new GrantLink(rs.getString("grant_status"), rs.getObject("user_role_id", UUID.class)) : null,
                tenantId, requestId);
        if (link == null) throw new ProcessRejectedException("P002 grant linkage not found");
        return link;
    }

    private String select(String suffix) {
        return """
                select p.id,p.tenant_id,p.business_no,p.workflow_instance_id,p.status,p.version_no,p.source_channel,
                       p.business_date,p.subject,p.reason,p.priority,p.risk_level,p.owner_center_id,p.owner_department_id,
                       p.owner_employee_id,p.planned_start_at,p.planned_finish_at,p.result_summary,p.actual_start_at,
                       p.actual_end_at,p.closed_at,g.target_user_id,g.target_identity_id,g.requested_role_id,g.user_role_id,
                       g.grant_status,g.effective_start_at,g.effective_end_at,g.executed_at,g.revoked_at
                  from iam.permission_request p
                  join iam.permission_request_grant g on g.tenant_id=p.tenant_id and g.permission_request_id=p.id
                """ + suffix;
    }

    private PermissionRequestService.PermissionRequest map(ResultSet rs) throws SQLException {
        return new PermissionRequestService.PermissionRequest(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("business_no"),
                rs.getObject("workflow_instance_id", UUID.class), rs.getString("status"), rs.getInt("version_no"),
                rs.getString("source_channel"), rs.getObject("business_date", LocalDate.class), rs.getString("subject"),
                rs.getString("reason"), rs.getString("priority"), rs.getString("risk_level"),
                rs.getObject("owner_center_id", UUID.class), rs.getObject("owner_department_id", UUID.class),
                rs.getObject("owner_employee_id", UUID.class), instant(rs,"planned_start_at"), instant(rs,"planned_finish_at"),
                rs.getString("result_summary"), instant(rs,"actual_start_at"), instant(rs,"actual_end_at"), instant(rs,"closed_at"),
                rs.getObject("target_user_id", UUID.class), rs.getObject("target_identity_id", UUID.class),
                rs.getObject("requested_role_id", UUID.class), rs.getObject("user_role_id", UUID.class), rs.getString("grant_status"),
                instant(rs,"effective_start_at"), instant(rs,"effective_end_at"), instant(rs,"executed_at"), instant(rs,"revoked_at"));
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new ProcessRejectedException("P002 JSON item cannot be serialized", ex); }
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private record GrantLink(String status, UUID userRoleId) {}
}
