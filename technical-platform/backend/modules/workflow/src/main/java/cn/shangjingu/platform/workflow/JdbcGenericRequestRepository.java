package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.GenericRequestService.FormRef;
import cn.shangjingu.platform.workflow.GenericRequestService.GenericRequest;
import java.math.BigDecimal;
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
public class JdbcGenericRequestRepository implements GenericRequestService.Repository {
    private final JdbcTemplate jdbc;

    public JdbcGenericRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode) {
        return jdbc
                .query(
                        """
                select v.id from workflow.wf_version v
                join workflow.wf_definition d on d.tenant_id=v.tenant_id and d.id=v.definition_id
                where v.tenant_id=? and d.process_code=? and d.enabled and not d.is_deleted
                  and v.status='PUBLISHED' and not v.is_deleted
                  and (v.effective_at is null or v.effective_at<=now())
                order by v.version_no desc,v.effective_at desc nulls last,v.created_at desc limit 1
                """,
                        (rs, n) -> rs.getObject(1, UUID.class),
                        tenantId,
                        processCode)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<FormRef> latestPublishedForm(UUID tenantId, String formCode, String processCode, String nodeCode) {
        return jdbc
                .query(
                        """
                select id,version_no from workflow.wf_form_definition
                 where tenant_id=? and form_code=? and process_code=? and node_code=?
                   and enabled and not is_deleted
                 order by version_no desc,created_at desc,id desc limit 1
                """,
                        (rs, n) -> new FormRef(rs.getObject("id", UUID.class), rs.getInt("version_no")),
                        tenantId,
                        formCode,
                        processCode,
                        nodeCode)
                .stream()
                .findFirst();
    }

    @Override
    public List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId, UUID excludedEmployeeId) {
        return jdbc.query(
                """
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
                  and ui.org_id=? and ui.employee_id<>?
                order by ui.employee_id
                """,
                (rs, n) -> rs.getObject(1, UUID.class),
                tenantId,
                permissionCode,
                orgId,
                excludedEmployeeId);
    }

    @Override
    public void insert(GenericRequest request, UUID actorId) {
        int inserted = jdbc.update(
                """
                insert into workflow.generic_request(
                    id,tenant_id,business_no,workflow_instance_id,status,version_no,created_by,updated_by,
                    request_type,subject,reason,requested_result,actual_amount,actual_end_at,actual_start_at,
                    business_date,result_summary)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?,?)
                """,
                request.id(),
                request.tenantId(),
                request.businessNo(),
                null,
                request.status(),
                request.versionNo(),
                actorId,
                actorId,
                request.requestType(),
                request.subject(),
                request.reason(),
                request.requestedResult(),
                null,
                null,
                request.businessDate(),
                "");
        if (inserted != 1) throw new ProcessRejectedException("P004 generic request insert failed");
    }

    @Override
    public int bindWorkflowAndMove(
            UUID tenantId, UUID id, int expectedVersion, UUID workflowInstanceId, String status, UUID actorId) {
        return jdbc.update(
                """
                update workflow.generic_request
                   set workflow_instance_id=?,status=?,version_no=version_no+1,updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and not is_deleted
                """,
                workflowInstanceId,
                status,
                actorId,
                tenantId,
                id,
                expectedVersion);
    }

    @Override
    public int moveStatus(
            UUID tenantId,
            UUID id,
            int expectedVersion,
            String status,
            BigDecimal actualAmount,
            String resultSummary,
            Instant closedAt,
            UUID actorId) {
        return jdbc.update(
                """
                update workflow.generic_request
                   set status=?,actual_amount=coalesce(?,actual_amount),
                       result_summary=coalesce(cast(? as text),result_summary),
                       actual_end_at=coalesce(?,actual_end_at),version_no=version_no+1,
                       updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and not is_deleted
                """,
                status,
                actualAmount,
                resultSummary,
                timestamp(closedAt),
                actorId,
                tenantId,
                id,
                expectedVersion);
    }

    @Override
    public Optional<UUID> lastActorAtNodeAction(
            UUID tenantId, UUID workflowInstanceId, String nodeCode, String actionCode) {
        return jdbc
                .query(
                        """
                select operator_id from workflow.wf_action_log
                 where tenant_id=? and instance_id=? and from_status=? and action_code=?
                   and operator_id is not null and not is_deleted
                 order by occurred_at desc,id desc limit 1
                """,
                        (rs, n) -> rs.getObject(1, UUID.class),
                        tenantId,
                        workflowInstanceId,
                        nodeCode,
                        actionCode)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<GenericRequest> find(UUID tenantId, UUID id) {
        return jdbc
                .query(
                        select("where gr.tenant_id=? and gr.id=? and not gr.is_deleted"),
                        (rs, n) -> map(rs),
                        tenantId,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public List<GenericRequest> list(UUID tenantId) {
        return jdbc.query(
                select("where gr.tenant_id=? and not gr.is_deleted order by gr.created_at desc,gr.id desc"),
                (rs, n) -> map(rs),
                tenantId);
    }

    private String select(String suffix) {
        return """
                select gr.id,gr.tenant_id,gr.business_no,gr.workflow_instance_id,gr.status,gr.version_no,
                       gr.request_type,gr.subject,gr.reason,gr.requested_result,gr.business_date,gr.actual_amount,
                       gr.actual_end_at,gr.result_summary,gr.updated_at,gr.created_by owner_employee_id,
                       owner.primary_org_id owner_center_id,
                       wi.instance_no workflow_instance_no,wi.current_node_code,wi.priority,
                       coalesce(wi.context_snapshot->>'riskLevel','NORMAL') risk_level,
                       nullif(wi.context_snapshot->>'amount','')::numeric amount,
                       initial_form.id initial_submission_id,initial_form.submission_no initial_submission_no,
                       coalesce(initial_form.form_version,0) initial_form_version
                  from workflow.generic_request gr
                  left join org.employee owner
                    on owner.tenant_id=gr.tenant_id and owner.id=gr.created_by and not owner.is_deleted
                  left join workflow.wf_instance wi
                    on wi.tenant_id=gr.tenant_id and wi.id=gr.workflow_instance_id and not wi.is_deleted
                  left join lateral (
                    select s.id,s.submission_no,s.form_version
                      from workflow.wf_submission s
                      join workflow.wf_form_definition f
                        on f.tenant_id=s.tenant_id and f.id=s.form_definition_id and not f.is_deleted
                     where s.tenant_id=gr.tenant_id and s.instance_id=gr.workflow_instance_id
                       and f.form_code='EMP-P004-F01' and not s.is_deleted
                     order by s.submitted_at desc,s.id desc limit 1
                  ) initial_form on true
                """
                + suffix;
    }

    private GenericRequest map(ResultSet rs) throws SQLException {
        return new GenericRequest(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("business_no"),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("workflow_instance_no"),
                rs.getString("current_node_code"),
                rs.getString("status"),
                rs.getInt("version_no"),
                rs.getString("request_type"),
                rs.getString("subject"),
                rs.getString("reason"),
                rs.getString("requested_result"),
                localDate(rs, "business_date"),
                rs.getBigDecimal("actual_amount"),
                instant(rs, "actual_end_at"),
                rs.getObject("owner_center_id", UUID.class),
                rs.getObject("owner_employee_id", UUID.class),
                rs.getString("priority"),
                rs.getString("risk_level"),
                rs.getBigDecimal("amount"),
                rs.getObject("initial_submission_id", UUID.class),
                rs.getString("initial_submission_no"),
                rs.getInt("initial_form_version"),
                rs.getString("result_summary"),
                instant(rs, "updated_at"));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

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
