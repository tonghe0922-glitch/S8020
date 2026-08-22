package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.workflow.NoticeReceiptService.AudienceMember;
import cn.shangjingu.platform.workflow.NoticeReceiptService.FormRef;
import cn.shangjingu.platform.workflow.NoticeReceiptService.Notice;
import cn.shangjingu.platform.workflow.NoticeReceiptService.Recipient;
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
public class JdbcNoticeReceiptRepository implements NoticeReceiptService.Repository {
    private final JdbcTemplate jdbc;

    public JdbcNoticeReceiptRepository(JdbcTemplate jdbc) {
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
    public List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId) {
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
                  and ui.org_id=?
                order by ui.employee_id
                """,
                (rs, n) -> rs.getObject(1, UUID.class),
                tenantId,
                permissionCode,
                orgId);
    }

    @Override
    public List<AudienceMember> resolveRecipients(UUID tenantId, UUID centerId, String positionCode) {
        return jdbc.query(
                """
                select distinct on (ui.employee_id)
                       ui.employee_id,ui.id identity_id,ui.org_id,ui.position_id,p.position_code
                  from iam.user_identity ui
                  join org.employee e on e.tenant_id=ui.tenant_id and e.id=ui.employee_id
                    and e.employment_status='ACTIVE' and not e.is_deleted
                  left join org.position p on p.tenant_id=ui.tenant_id and p.id=ui.position_id and not p.is_deleted
                 where ui.tenant_id=? and ui.org_id=? and not ui.is_deleted
                   and ui.effective_start_at<=now() and (ui.effective_end_at is null or ui.effective_end_at>now())
                   and (cast(? as varchar) is null or p.position_code=cast(? as varchar))
                 order by ui.employee_id,ui.is_primary desc,ui.effective_start_at desc,ui.id
                """,
                (rs, n) -> new AudienceMember(
                        rs.getObject("employee_id", UUID.class),
                        rs.getObject("identity_id", UUID.class),
                        rs.getObject("org_id", UUID.class),
                        rs.getObject("position_id", UUID.class),
                        rs.getString("position_code")),
                tenantId,
                centerId,
                positionCode,
                positionCode);
    }

    @Override
    public int nextPolicyVersion(UUID tenantId, String policyCode) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(cast(? as text),0))",
                rs -> {
                    if (rs.next()) rs.getObject(1);
                    return null;
                },
                tenantId + "|P005|" + policyCode);
        Integer value = jdbc.queryForObject(
                """
                select coalesce(max(policy_version),0)+1
                  from collaboration.notice
                 where tenant_id=? and policy_code=? and employee_event_type='P005_POLICY_NOTICE' and not is_deleted
                """,
                Integer.class,
                tenantId,
                policyCode);
        return value == null ? 1 : value;
    }

    @Override
    public void insertNotice(Notice notice, String recipientScopeJson, UUID actorId) {
        int inserted = jdbc.update(
                """
                insert into collaboration.notice(
                    id,tenant_id,created_by,updated_by,business_no,workflow_instance_id,employee_event_type,
                    official_content,official_subject,official_type,period_or_course_no,
                    source_channel,version_no,visibility_level,owner_center_id,owner_employee_id,status,priority,
                    business_date,planned_start_at,planned_finish_at,venue_channel,actual_start_at,
                    policy_code,policy_version,published_at,target_center_id,target_position_code,
                    understanding_pass_score,execution_due_at)
                values (?,?,?,?,?,null,'P005_POLICY_NOTICE',?,?,?,?,'PC',?,?,?,?,?,'NORMAL',?,?,?,?,now(),?,?,?,?,?,?,?)
                """,
                notice.id(),
                notice.tenantId(),
                actorId,
                actorId,
                notice.businessNo(),
                notice.officialContent(),
                notice.officialSubject(),
                notice.officialType(),
                notice.periodOrCourseNo(),
                notice.versionNo(),
                notice.visibilityLevel(),
                notice.ownerCenterId(),
                notice.ownerEmployeeId(),
                notice.status(),
                notice.businessDate(),
                timestamp(notice.effectiveStartAt()),
                timestamp(notice.effectiveEndAt()),
                notice.venueChannel(),
                notice.policyCode(),
                notice.policyVersion(),
                timestamp(notice.publishedAt()),
                notice.targetCenterId(),
                notice.targetPositionCode(),
                notice.understandingPassScore(),
                timestamp(notice.executionDueAt()));
        if (inserted != 1) throw new ProcessRejectedException("P005 canonical notice insert failed");
    }

    @Override
    public void insertRecipients(UUID tenantId, UUID noticeId, List<AudienceMember> recipients) {
        for (AudienceMember recipient : recipients) {
            int inserted = jdbc.update(
                    """
                    insert into collaboration.notice_recipient(
                        id,tenant_id,notice_id,employee_id,identity_id,org_id,position_id,position_code,delivery_status)
                    values (gen_random_uuid(),?,?,?,?,?,?,?,'QUEUED')
                    on conflict (tenant_id,notice_id,employee_id) do nothing
                    """,
                    tenantId,
                    noticeId,
                    recipient.employeeId(),
                    recipient.identityId(),
                    recipient.orgId(),
                    recipient.positionId(),
                    recipient.positionCode());
            if (inserted != 1) throw new ProcessRejectedException("P005 duplicate or concurrent audience resolution");
        }
    }

    @Override
    public int bindWorkflowAndMove(
            UUID tenantId, UUID noticeId, int expectedVersion, UUID workflowInstanceId, String status, UUID actorId) {
        return jdbc.update(
                """
                update collaboration.notice
                   set workflow_instance_id=?,status=?,version_no=version_no+1,updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and employee_event_type='P005_POLICY_NOTICE' and not is_deleted
                """,
                workflowInstanceId,
                status,
                actorId,
                tenantId,
                noticeId,
                expectedVersion);
    }

    @Override
    public int moveStatus(
            UUID tenantId,
            UUID noticeId,
            int expectedVersion,
            String status,
            Instant archivedAt,
            Instant closedAt,
            UUID actorId) {
        return jdbc.update(
                """
                update collaboration.notice
                   set status=?,archived_at=coalesce(?,archived_at),actual_end_at=coalesce(?,actual_end_at),
                       version_no=version_no+1,updated_by=?,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and employee_event_type='P005_POLICY_NOTICE' and not is_deleted
                """,
                status,
                timestamp(archivedAt),
                timestamp(closedAt),
                actorId,
                tenantId,
                noticeId,
                expectedVersion);
    }

    @Override
    public int markRead(UUID tenantId, UUID recipientId, int expectedVersion, UUID actorId) {
        return jdbc.update(
                """
                update collaboration.notice_recipient
                   set read_at=now(),version_no=version_no+1,updated_at=now()
                 where tenant_id=? and id=? and employee_id=? and version_no=? and delivered_at is not null
                   and read_at is null and not is_deleted
                """,
                tenantId,
                recipientId,
                actorId,
                expectedVersion);
    }

    @Override
    public int markConfirmed(UUID tenantId, UUID recipientId, int expectedVersion, UUID actorId) {
        return jdbc.update(
                """
                update collaboration.notice_recipient
                   set confirmed_at=now(),version_no=version_no+1,updated_at=now()
                 where tenant_id=? and id=? and employee_id=? and version_no=? and read_at is not null
                   and confirmed_at is null and not is_deleted
                """,
                tenantId,
                recipientId,
                actorId,
                expectedVersion);
    }

    @Override
    public int markUnderstanding(
            UUID tenantId, UUID recipientId, int expectedVersion, int score, boolean passed, UUID actorId) {
        return jdbc.update(
                """
                update collaboration.notice_recipient
                   set understanding_score=?,
                       understanding_passed_at=case when ? then coalesce(understanding_passed_at,now()) else understanding_passed_at end,
                       version_no=version_no+1,updated_at=now()
                 where tenant_id=? and id=? and employee_id=? and version_no=? and confirmed_at is not null
                   and understanding_passed_at is null and not is_deleted
                """,
                score,
                passed,
                tenantId,
                recipientId,
                actorId,
                expectedVersion);
    }

    @Override
    public int markExecuted(UUID tenantId, UUID recipientId, int expectedVersion, String summary, UUID actorId) {
        return jdbc.update(
                """
                update collaboration.notice_recipient
                   set execution_summary=?,executed_at=now(),version_no=version_no+1,updated_at=now()
                 where tenant_id=? and id=? and employee_id=? and version_no=? and understanding_passed_at is not null
                   and executed_at is null and not is_deleted
                """,
                summary,
                tenantId,
                recipientId,
                actorId,
                expectedVersion);
    }

    @Override
    public int markAccepted(UUID tenantId, UUID recipientId, int expectedVersion, UUID actorId) {
        return jdbc.update(
                """
                update collaboration.notice_recipient
                   set accepted_at=now(),accepted_by=?,version_no=version_no+1,updated_at=now()
                 where tenant_id=? and id=? and version_no=? and executed_at is not null and accepted_at is null and not is_deleted
                """,
                actorId,
                tenantId,
                recipientId,
                expectedVersion);
    }

    @Override
    public void appendReceiptEvent(
            UUID tenantId,
            UUID noticeId,
            UUID recipientId,
            UUID employeeId,
            UUID actorId,
            String eventType,
            String evidenceJson) {
        int inserted = jdbc.update(
                """
                insert into collaboration.notice_receipt_event(
                    id,tenant_id,notice_id,recipient_id,employee_id,actor_employee_id,event_type,evidence_json)
                values (gen_random_uuid(),?,?,?,?,?,?,cast(? as jsonb))
                """,
                tenantId,
                noticeId,
                recipientId,
                employeeId,
                actorId,
                eventType,
                evidenceJson == null ? "{}" : evidenceJson);
        if (inserted != 1) throw new ProcessRejectedException("P005 receipt event append failed");
    }

    @Override
    public Optional<Notice> findNotice(UUID tenantId, UUID noticeId) {
        return jdbc
                .query(
                        selectNotice(
                                "where n.tenant_id=? and n.id=? and n.employee_event_type='P005_POLICY_NOTICE' and not n.is_deleted"),
                        (rs, n) -> mapNotice(rs),
                        tenantId,
                        noticeId)
                .stream()
                .findFirst();
    }

    @Override
    public List<Notice> listNotices(UUID tenantId) {
        return jdbc.query(
                selectNotice(
                        "where n.tenant_id=? and n.employee_event_type='P005_POLICY_NOTICE' and not n.is_deleted order by n.published_at desc,n.id desc"),
                (rs, n) -> mapNotice(rs),
                tenantId);
    }

    @Override
    public List<Recipient> listRecipients(UUID tenantId, UUID noticeId) {
        return jdbc.query(
                """
                select id,notice_id,employee_id,identity_id,org_id,position_id,position_code,delivery_status,
                       delivered_at,read_at,confirmed_at,understanding_score,understanding_passed_at,
                       execution_summary,executed_at,accepted_at,accepted_by,last_reminded_at,escalation_count,
                       version_no,updated_at
                  from collaboration.notice_recipient
                 where tenant_id=? and notice_id=? and not is_deleted
                 order by employee_id,id
                """,
                (rs, n) -> mapRecipient(rs),
                tenantId,
                noticeId);
    }

    private String selectNotice(String suffix) {
        return """
                select n.id,n.tenant_id,n.business_no,n.workflow_instance_id,n.status,n.version_no,
                       n.policy_code,n.policy_version,n.official_subject,n.official_type,n.official_content,
                       n.period_or_course_no,n.visibility_level,n.venue_channel,n.owner_center_id,n.owner_employee_id,
                       n.target_center_id,n.target_position_code,n.understanding_pass_score,n.published_at,n.business_date,
                       n.planned_start_at effective_start_at,n.planned_finish_at effective_end_at,
                       n.execution_due_at,n.archived_at,n.actual_end_at,n.updated_at,
                       wi.instance_no workflow_instance_no,wi.current_node_code
                  from collaboration.notice n
                  left join workflow.wf_instance wi
                    on wi.tenant_id=n.tenant_id and wi.id=n.workflow_instance_id and not wi.is_deleted
                """
                + suffix;
    }

    private Notice mapNotice(ResultSet rs) throws SQLException {
        return new Notice(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("business_no"),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("workflow_instance_no"),
                rs.getString("current_node_code"),
                rs.getString("status"),
                rs.getInt("version_no"),
                rs.getString("policy_code"),
                rs.getInt("policy_version"),
                rs.getString("official_subject"),
                rs.getString("official_type"),
                rs.getString("official_content"),
                rs.getString("period_or_course_no"),
                rs.getString("visibility_level"),
                rs.getString("venue_channel"),
                rs.getObject("owner_center_id", UUID.class),
                rs.getObject("owner_employee_id", UUID.class),
                rs.getObject("target_center_id", UUID.class),
                rs.getString("target_position_code"),
                rs.getInt("understanding_pass_score"),
                instant(rs, "published_at"),
                localDate(rs, "business_date"),
                instant(rs, "effective_start_at"),
                instant(rs, "effective_end_at"),
                instant(rs, "execution_due_at"),
                instant(rs, "archived_at"),
                instant(rs, "actual_end_at"),
                instant(rs, "updated_at"));
    }

    private Recipient mapRecipient(ResultSet rs) throws SQLException {
        Integer score = (Integer) rs.getObject("understanding_score");
        return new Recipient(
                rs.getObject("id", UUID.class),
                rs.getObject("notice_id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("identity_id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("position_id", UUID.class),
                rs.getString("position_code"),
                rs.getString("delivery_status"),
                instant(rs, "delivered_at"),
                instant(rs, "read_at"),
                instant(rs, "confirmed_at"),
                score,
                instant(rs, "understanding_passed_at"),
                rs.getString("execution_summary"),
                instant(rs, "executed_at"),
                instant(rs, "accepted_at"),
                rs.getObject("accepted_by", UUID.class),
                instant(rs, "last_reminded_at"),
                rs.getInt("escalation_count"),
                rs.getInt("version_no"),
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
