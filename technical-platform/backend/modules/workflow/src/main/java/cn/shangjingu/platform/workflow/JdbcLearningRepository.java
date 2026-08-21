package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.workflow.LearningService.Evidence;
import cn.shangjingu.platform.workflow.LearningService.FormRef;
import cn.shangjingu.platform.workflow.LearningService.LearningRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLearningRepository implements LearningService.Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLearningRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<UUID> workflowVersion(UUID tenantId) {
        return jdbc.query(
                        """
                        select v.id
                        from workflow.wf_version v
                        join workflow.wf_definition d
                          on d.tenant_id=v.tenant_id and d.id=v.definition_id
                        where v.tenant_id=?
                          and d.process_code='P010'
                          and d.enabled
                          and not d.is_deleted
                          and v.status='PUBLISHED'
                          and not v.is_deleted
                        order by v.version_no desc
                        limit 1
                        """,
                        (result, row) -> result.getObject(1, UUID.class),
                        tenantId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<FormRef> form(UUID tenantId) {
        return jdbc.query(
                        """
                        select id,version_no
                        from workflow.wf_form_definition
                        where tenant_id=?
                          and form_code='CTR-P010-F03'
                          and process_code='P010'
                          and node_code='S01'
                          and enabled
                          and not is_deleted
                        order by version_no desc
                        limit 1
                        """,
                        (result, row) ->
                                new FormRef(
                                        result.getObject(1, UUID.class), result.getInt(2)),
                        tenantId)
                .stream()
                .findFirst();
    }

    @Override
    public List<UUID> permissionCandidates(
            UUID tenantId, String permission, UUID orgId) {
        return jdbc.query(
                """
                select distinct ui.employee_id
                from iam.user_role ur
                join iam.role r
                  on r.tenant_id=ur.tenant_id
                 and r.id=ur.role_id
                 and r.enabled
                 and not r.is_deleted
                join iam.role_permission rp
                  on rp.tenant_id=r.tenant_id
                 and rp.role_id=r.id
                 and not rp.is_deleted
                join iam.permission p
                  on p.tenant_id=rp.tenant_id
                 and p.id=rp.permission_id
                 and not p.is_deleted
                join iam.user_identity ui
                  on ui.tenant_id=ur.tenant_id
                 and ui.user_id=ur.user_id
                 and not ui.is_deleted
                 and (ur.identity_id is null or ur.identity_id=ui.id)
                where ur.tenant_id=?
                  and p.permission_code=?
                  and ui.org_id=?
                  and not ur.is_deleted
                  and ur.effective_start_at<=now()
                  and (ur.effective_end_at is null or ur.effective_end_at>now())
                """,
                (result, row) -> result.getObject(1, UUID.class),
                tenantId,
                permission,
                orgId);
    }

    @Override
    public boolean activeEmployeeInCenter(
            UUID tenantId, UUID employeeId, UUID orgId) {
        Boolean active =
                jdbc.queryForObject(
                        """
                        select exists(
                          select 1
                          from org.employee e
                          where e.tenant_id=?
                            and e.id=?
                            and e.employment_status='ACTIVE'
                            and not e.is_deleted
                            and (
                              e.primary_org_id=?
                              or exists(
                                select 1
                                from org.employee_position ep
                                where ep.tenant_id=e.tenant_id
                                  and ep.employee_id=e.id
                                  and ep.org_id=?
                                  and ep.status='ACTIVE'
                                  and ep.effective_start_date<=current_date
                                  and (ep.effective_end_date is null
                                       or ep.effective_end_date>=current_date)
                                  and not ep.is_deleted
                              )
                            )
                        )
                        """,
                        Boolean.class,
                        tenantId,
                        employeeId,
                        orgId,
                        orgId);
        return Boolean.TRUE.equals(active);
    }

    @Override
    public void insert(
            LearningRecord record,
            String reason,
            String courseTeamName,
            String riskLevel,
            String learnerProfile,
            Instant plannedStartAt,
            Instant plannedFinishAt,
            UUID actor) {
        jdbc.update(
                """
                insert into learning.learning_assignment(
                  id,tenant_id,business_no,status,version_no,created_by,updated_by,
                  source_channel,business_date,subject,reason,priority,risk_level,
                  owner_center_id,owner_employee_id,planned_start_at,planned_finish_at,
                  completion_rate,content_version,course_team_name,course_version_id,
                  learner_profile,period_or_course_no,phase_node_code)
                values(
                  ?,?,?,?,0,?,?,
                  ,'PORTAL',current_date,?,?,'NORMAL',?
                  ,?,?,?,?
                  ,0,?,?,?,?,?,'S01')
                """,
                record.id(),
                record.tenantId(),
                record.businessNo(),
                record.status(),
                actor,
                actor,
                record.subject(),
                reason,
                riskLevel,
                record.ownerCenterId(),
                record.ownerEmployeeId(),
                timestamp(plannedStartAt),
                timestamp(plannedFinishAt),
                record.contentVersion(),
                courseTeamName,
                record.courseVersionId(),
                learnerProfile,
                record.periodOrCourseNo());
    }

    @Override
    public Optional<LearningRecord> find(UUID tenantId, UUID id) {
        return jdbc.query(
                        select("where a.tenant_id=? and a.id=? and not a.is_deleted"),
                        (result, row) -> map(result),
                        tenantId,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public List<LearningRecord> list(UUID tenantId) {
        return jdbc.query(
                select(
                        "where a.tenant_id=? and not a.is_deleted "
                                + "order by a.updated_at desc,a.id desc"),
                (result, row) -> map(result),
                tenantId);
    }

    @Override
    public List<Evidence> evidence(UUID tenantId, UUID id) {
        return jdbc.query(
                """
                select id,evidence_type,actor_employee_id,score_1000,completion_rate,
                       practical_result,evidence_text,evidence_json,created_at
                from learning.learning_assignment_evidence
                where tenant_id=? and assignment_id=?
                order by created_at,id
                """,
                (result, row) ->
                        new Evidence(
                                result.getObject(1, UUID.class),
                                result.getString(2),
                                result.getObject(3, UUID.class),
                                result.getObject(4) == null ? null : result.getLong(4),
                                result.getBigDecimal(5),
                                result.getString(6),
                                result.getString(7),
                                json(result.getString(8)),
                                instant(result, "created_at")),
                tenantId,
                id);
    }

    @Override
    public int bindWorkflow(
            UUID tenantId,
            UUID id,
            int version,
            UUID workflowId,
            String node,
            String status,
            UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set workflow_instance_id=?,
                    phase_node_code=?,
                    status=?,
                    version_no=version_no+1,
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and version_no=?
                  and not is_deleted
                """,
                workflowId,
                node,
                status,
                actor,
                tenantId,
                id,
                version);
    }

    @Override
    public int moveNode(
            UUID tenantId,
            UUID id,
            int version,
            String node,
            String status,
            Instant closed,
            UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set phase_node_code=?,
                    status=?,
                    closed_at=coalesce(?,closed_at),
                    version_no=version_no+1,
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and version_no=?
                  and not is_deleted
                """,
                node,
                status,
                timestamp(closed),
                actor,
                tenantId,
                id,
                version);
    }

    @Override
    public void appendEvidence(
            UUID tenantId,
            UUID id,
            String type,
            UUID actor,
            Long score,
            BigDecimal progress,
            String practical,
            String text,
            JsonNode json) {
        jdbc.update(
                """
                insert into learning.learning_assignment_evidence(
                  id,tenant_id,assignment_id,evidence_type,actor_employee_id,
                  score_1000,completion_rate,practical_result,evidence_text,evidence_json)
                values(gen_random_uuid(),?,?,?,?,?,?,?,?,cast(? as jsonb))
                """,
                tenantId,
                id,
                type,
                actor,
                score,
                progress,
                practical,
                text,
                json == null ? null : json.toString());
    }

    @Override
    public int updateProgress(
            UUID tenantId, UUID id, BigDecimal progress, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set completion_rate=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and phase_node_code='S03' and not is_deleted
                """,
                progress,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markLearningCompleted(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set learning_completed_at=coalesce(learning_completed_at,now()),
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and completion_rate=100 and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int updateExam(UUID tenantId, UUID id, long score, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set score_1000=?,
                    exam_completed_at=coalesce(exam_completed_at,now()),
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and phase_node_code='S04' and not is_deleted
                """,
                score,
                actor,
                tenantId,
                id);
    }

    @Override
    public int updatePractical(
            UUID tenantId, UUID id, String result, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set practical_result=?,
                    practical_completed_at=coalesce(practical_completed_at,now()),
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and phase_node_code='S05' and not is_deleted
                """,
                result,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markContentPublished(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set content_published_at=coalesce(content_published_at,now()),
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markRiskAssigned(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set risk_assigned_at=coalesce(risk_assigned_at,now()),
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markCertified(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set certified_at=coalesce(certified_at,now()),
                    certified_by=?,
                    updated_by=?,
                    updated_at=now()
                where tenant_id=? and id=? and phase_node_code='S06' and not is_deleted
                """,
                actor,
                actor,
                tenantId,
                id);
    }

    @Override
    public int activateQualification(
            UUID tenantId,
            UUID id,
            LocalDate effective,
            LocalDate expire,
            UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set qualification_effective_date=?,
                    qualification_expire_date=?,
                    qualification_activated_at=coalesce(qualification_activated_at,now()),
                    updated_by=?,
                    updated_at=now()
                where tenant_id=?
                  and id=?
                  and certified_at is not null
                  and not is_deleted
                """,
                effective,
                expire,
                actor,
                tenantId,
                id);
    }

    @Override
    public List<UUID> linkPermissions(UUID tenantId, UUID id, UUID actor) {
        LearningRecord assignment = find(tenantId, id).orElseThrow();
        List<Identity> identities =
                jdbc.query(
                        """
                        select id,user_id,position_id
                        from iam.user_identity
                        where tenant_id=?
                          and employee_id=?
                          and org_id=?
                          and not is_deleted
                          and effective_start_at<=now()
                          and (effective_end_at is null or effective_end_at>now())
                        order by is_primary desc,effective_start_at desc
                        limit 1
                        """,
                        (result, row) ->
                                new Identity(
                                        result.getObject(1, UUID.class),
                                        result.getObject(2, UUID.class),
                                        result.getObject(3, UUID.class)),
                        tenantId,
                        assignment.ownerEmployeeId(),
                        assignment.ownerCenterId());
        if (identities.isEmpty()) {
            return List.of();
        }
        Identity identity = identities.getFirst();
        List<UUID> roles =
                jdbc.query(
                        """
                        select role_id
                        from learning.qualification_permission_binding
                        where tenant_id=?
                          and course_version_id=?
                          and enabled
                          and not is_deleted
                          and (position_id is null or position_id=?)
                        order by role_id
                        """,
                        (result, row) -> result.getObject(1, UUID.class),
                        tenantId,
                        assignment.courseVersionId(),
                        identity.positionId());
        List<UUID> linked = new ArrayList<>();
        for (UUID role : roles) {
            jdbc.update(
                    """
                    insert into iam.user_role(
                      id,tenant_id,created_by,updated_by,user_id,identity_id,role_id,
                      effective_start_at,effective_end_at,grant_source,created_at,updated_at,is_deleted)
                    select gen_random_uuid(),?,?,?,?,?,?,
                           (?::date::timestamp at time zone 'Asia/Shanghai'),
                           case when ?::date is null then null
                                else ((?::date+1)::timestamp at time zone 'Asia/Shanghai') end,
                           'QUALIFICATION',now(),now(),false
                    where not exists(
                      select 1
                      from iam.user_role ur
                      where ur.tenant_id=?
                        and ur.user_id=?
                        and ur.identity_id=?
                        and ur.role_id=?
                        and not ur.is_deleted
                        and ur.effective_start_at<
                            coalesce(
                              case when ?::date is null then null
                                   else ((?::date+1)::timestamp at time zone 'Asia/Shanghai') end,
                              'infinity'::timestamptz)
                        and coalesce(ur.effective_end_at,'infinity'::timestamptz)>
                            (?::date::timestamp at time zone 'Asia/Shanghai')
                    )
                    """,
                    tenantId,
                    actor,
                    actor,
                    identity.userId(),
                    identity.id(),
                    role,
                    assignment.qualificationEffectiveDate(),
                    assignment.qualificationExpireDate(),
                    assignment.qualificationExpireDate(),
                    tenantId,
                    identity.userId(),
                    identity.id(),
                    role,
                    assignment.qualificationExpireDate(),
                    assignment.qualificationExpireDate(),
                    assignment.qualificationEffectiveDate());
            linked.add(role);
        }
        return linked;
    }

    @Override
    public int markPermissionLinked(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set permission_linked_at=coalesce(permission_linked_at,now()),
                    updated_by=?,updated_at=now()
                where tenant_id=?
                  and id=?
                  and qualification_activated_at is not null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markRetrainingChecked(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set retraining_checked_at=coalesce(retraining_checked_at,now()),
                    updated_by=?,updated_at=now()
                where tenant_id=?
                  and id=?
                  and permission_linked_at is not null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    @Override
    public int markArchived(UUID tenantId, UUID id, UUID actor) {
        return jdbc.update(
                """
                update learning.learning_assignment
                set archived_at=coalesce(archived_at,now()),
                    updated_by=?,updated_at=now()
                where tenant_id=?
                  and id=?
                  and retraining_checked_at is not null
                  and not is_deleted
                """,
                actor,
                tenantId,
                id);
    }

    private String select(String suffix) {
        return """
               select a.id,a.tenant_id,a.business_no,a.workflow_instance_id,
                      wi.instance_no workflow_instance_no,
                      coalesce(wi.current_node_code,a.phase_node_code) current_node_code,
                      a.status,a.version_no,a.subject,a.owner_center_id,a.owner_employee_id,
                      a.content_version,a.course_version_id,a.period_or_course_no,
                      a.completion_rate,a.score_1000,a.practical_result,
                      a.qualification_effective_date,a.qualification_expire_date,
                      a.certified_at,a.certified_by,a.permission_linked_at,a.archived_at,a.updated_at
               from learning.learning_assignment a
               left join workflow.wf_instance wi
                 on wi.tenant_id=a.tenant_id
                and wi.id=a.workflow_instance_id
                and not wi.is_deleted
               """
                + suffix;
    }

    private LearningRecord map(ResultSet result) throws SQLException {
        return new LearningRecord(
                result.getObject("id", UUID.class),
                result.getObject("tenant_id", UUID.class),
                result.getString("business_no"),
                result.getObject("workflow_instance_id", UUID.class),
                result.getString("workflow_instance_no"),
                result.getString("current_node_code"),
                result.getString("status"),
                result.getInt("version_no"),
                result.getString("subject"),
                result.getObject("owner_center_id", UUID.class),
                result.getObject("owner_employee_id", UUID.class),
                result.getString("content_version"),
                result.getString("course_version_id"),
                result.getString("period_or_course_no"),
                result.getBigDecimal("completion_rate"),
                result.getObject("score_1000") == null
                        ? null
                        : result.getLong("score_1000"),
                result.getString("practical_result"),
                result.getObject("qualification_effective_date", LocalDate.class),
                result.getObject("qualification_expire_date", LocalDate.class),
                instant(result, "certified_at"),
                result.getObject("certified_by", UUID.class),
                instant(result, "permission_linked_at"),
                instant(result, "archived_at"),
                instant(result, "updated_at"));
    }

    private JsonNode json(String value) {
        try {
            return value == null ? null : mapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid P010 evidence JSON", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet result, String column)
            throws SQLException {
        Object value = result.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((java.time.ZonedDateTime) value).toInstant();
    }

    private record Identity(UUID id, UUID userId, UUID positionId) {}
}
