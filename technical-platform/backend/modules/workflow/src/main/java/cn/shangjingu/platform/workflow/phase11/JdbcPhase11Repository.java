package cn.shangjingu.platform.workflow.phase11;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPhase11Repository implements Phase11Repository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcPhase11Repository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<UUID> latestPublishedWorkflowVersion(UUID tenantId, String processCode) {
        return jdbc
                .query(
                        """
                        select v.id
                        from workflow.wf_version v
                        join workflow.wf_definition d
                          on d.tenant_id=v.tenant_id and d.id=v.definition_id
                        where v.tenant_id=:tenantId
                          and d.process_code=:processCode
                          and d.enabled and not d.is_deleted
                          and v.status='PUBLISHED' and not v.is_deleted
                          and (v.effective_at is null or v.effective_at<=now())
                        order by v.version_no desc,v.created_at desc,v.id desc
                        limit 1
                        """,
                        params("tenantId", tenantId, "processCode", processCode),
                        (rs, rowNum) -> rs.getObject("id", UUID.class))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<FormRef> latestPublishedForm(UUID tenantId, String formCode, String processCode, String nodeCode) {
        return jdbc
                .query(
                        """
                        select id,version_no
                        from workflow.wf_form_definition
                        where tenant_id=:tenantId
                          and form_code=:formCode
                          and process_code=:processCode
                          and node_code=:nodeCode
                          and enabled and not is_deleted
                        order by version_no desc,created_at desc,id desc
                        limit 1
                        """,
                        params(
                                "tenantId", tenantId,
                                "formCode", formCode,
                                "processCode", processCode,
                                "nodeCode", nodeCode),
                        (rs, rowNum) -> new FormRef(rs.getObject("id", UUID.class), rs.getInt("version_no")))
                .stream()
                .findFirst();
    }

    @Override
    public List<UUID> permissionCandidates(UUID tenantId, String permissionCode, UUID orgId) {
        return jdbc.query(
                """
                select distinct ui.employee_id
                from iam.user_role ur
                join iam.role r
                  on r.tenant_id=ur.tenant_id and r.id=ur.role_id
                 and r.enabled and not r.is_deleted
                join iam.role_permission rp
                  on rp.tenant_id=r.tenant_id and rp.role_id=r.id and not rp.is_deleted
                join iam.permission p
                  on p.tenant_id=rp.tenant_id and p.id=rp.permission_id and not p.is_deleted
                join iam.user_identity ui
                  on ui.tenant_id=ur.tenant_id and ui.user_id=ur.user_id and not ui.is_deleted
                 and (ur.identity_id is null or ur.identity_id=ui.id)
                 and ui.effective_start_at<=now()
                 and (ui.effective_end_at is null or ui.effective_end_at>now())
                join org.employee e
                  on e.tenant_id=ui.tenant_id and e.id=ui.employee_id
                 and e.employment_status='ACTIVE' and not e.is_deleted
                where ur.tenant_id=:tenantId
                  and p.permission_code=:permissionCode
                  and ui.org_id=:orgId
                  and not ur.is_deleted
                  and ur.effective_start_at<=now()
                  and (ur.effective_end_at is null or ur.effective_end_at>now())
                order by ui.employee_id
                """,
                params(
                        "tenantId", tenantId,
                        "permissionCode", permissionCode,
                        "orgId", orgId),
                (rs, rowNum) -> rs.getObject("employee_id", UUID.class));
    }

    @Override
    public boolean activeEmployeeInOrg(UUID tenantId, UUID orgId, UUID employeeId) {
        Boolean result = jdbc.queryForObject(
                """
                select exists(
                    select 1
                    from org.employee e
                    join iam.user_identity ui
                      on ui.tenant_id=e.tenant_id and ui.employee_id=e.id and not ui.is_deleted
                    where e.tenant_id=:tenantId and e.id=:employeeId
                      and e.employment_status='ACTIVE' and not e.is_deleted
                      and ui.org_id=:orgId
                      and ui.effective_start_at<=now()
                      and (ui.effective_end_at is null or ui.effective_end_at>now())
                )
                """,
                params(
                        "tenantId", tenantId,
                        "orgId", orgId,
                        "employeeId", employeeId),
                Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void insert(Phase11Process process, Phase11Record record, Phase11CreateData data, UUID actorId) {
        if (process != Phase11Process.P011) {
            throw unsupported(process);
        }
        int inserted = jdbc.update(
                """
                insert into performance.performance_cycle(
                    id,tenant_id,business_no,workflow_instance_id,status,current_node_code,version_no,
                    created_by,updated_by,source_channel,business_date,subject,reason,priority,risk_level,
                    owner_center_id,owner_employee_id,fact_occurred_at,fact_summary,content_version,
                    employee_event_type,period_or_course_no,goal_summary)
                values(
                    :id,:tenantId,:businessNo,null,:status,'S01',0,
                    :actorId,:actorId,'PORTAL',:businessDate,:subject,:reason,:priority,:riskLevel,
                    :ownerCenterId,:ownerEmployeeId,:factOccurredAt,:factSummary,:contentVersion,
                    'P011_PERFORMANCE',:periodNo,:goalSummary)
                """,
                new MapSqlParameterSource()
                        .addValue("id", record.id())
                        .addValue("tenantId", record.tenantId())
                        .addValue("businessNo", record.businessNo())
                        .addValue("status", record.status())
                        .addValue("actorId", actorId)
                        .addValue("businessDate", record.businessDate())
                        .addValue("subject", record.subject())
                        .addValue("reason", record.reason())
                        .addValue("priority", record.priority())
                        .addValue("riskLevel", record.riskLevel())
                        .addValue("ownerCenterId", record.ownerCenterId())
                        .addValue("ownerEmployeeId", record.ownerEmployeeId())
                        .addValue("factOccurredAt", timestamp(record.factOccurredAt()))
                        .addValue("factSummary", record.factSummary())
                        .addValue("contentVersion", data.contentVersion())
                        .addValue("periodNo", data.periodNo())
                        .addValue("goalSummary", data.factSummary()));
        requireSingle(inserted, process.code() + " canonical insert");
    }

    @Override
    public int bindWorkflow(
            Phase11Process process,
            UUID tenantId,
            UUID recordId,
            int expectedVersion,
            UUID workflowInstanceId,
            String nodeCode,
            String status,
            UUID actorId) {
        if (process != Phase11Process.P011) {
            throw unsupported(process);
        }
        return jdbc.update(
                """
                update performance.performance_cycle
                set workflow_instance_id=:workflowInstanceId,
                    current_node_code=:nodeCode,
                    status=:status,
                    version_no=version_no+1,
                    updated_by=:actorId,
                    updated_at=now()
                where tenant_id=:tenantId and id=:recordId
                  and version_no=:expectedVersion
                  and employee_event_type='P011_PERFORMANCE'
                  and not is_deleted
                """,
                params(
                        "workflowInstanceId", workflowInstanceId,
                        "nodeCode", nodeCode,
                        "status", status,
                        "actorId", actorId,
                        "tenantId", tenantId,
                        "recordId", recordId,
                        "expectedVersion", expectedVersion));
    }

    @Override
    public int advance(
            Phase11Process process,
            Phase11Record current,
            String action,
            String targetNode,
            String status,
            Phase11ActionData data,
            UUID actorId) {
        if (process != Phase11Process.P011) {
            throw unsupported(process);
        }
        MapSqlParameterSource parameters = params(
                        "tenantId", current.tenantId(),
                        "recordId", current.id(),
                        "expectedVersion", data.expectedVersion(),
                        "expectedNode", current.currentNodeCode(),
                        "targetNode", targetNode,
                        "status", status,
                        "actorId", actorId)
                .addValue("summary", trimToNull(data.summary()))
                .addValue("score1000", data.score1000())
                .addValue("appealStatus", Boolean.TRUE.equals(data.appealRequested()) ? "SUBMITTED" : "NO_APPEAL")
                .addValue("appealReason", trimToNull(data.appealReason()))
                .addValue("decision", trimToNull(data.decision()));
        String domainSet =
                switch (action) {
                    case "CONFIRM_TARGETS" -> "employee_confirmed_at=coalesce(employee_confirmed_at,now()),";
                    case "RECORD_COACHING" -> "coaching_summary=:summary,";
                    case "COLLECT_FACTS" -> "authoritative_data_summary=:summary,";
                    case "CALCULATE_SCORE" -> "score_1000=:score1000,";
                    case "CALIBRATE" -> "calibrated_score_1000=:score1000,";
                    case "SUBMIT_APPEAL_DECISION" -> "appeal_status=:appealStatus,appeal_reason=:appealReason,feedback_confirmed_at=now(),";
                    case "RESOLVE_APPEAL" -> "appeal_reviewer_id=:actorId,appeal_decision=:decision,appeal_status='RESOLVED',";
                    case "EXECUTE_IMPACT" -> "impact_executed_at=coalesce(impact_executed_at,now()),";
                    case "ARCHIVE" -> "archived_at=coalesce(archived_at,now()),closed_at=coalesce(closed_at,now()),"
                            + "actual_end_at=coalesce(actual_end_at,now()),";
                    default -> "";
                };
        return jdbc.update(
                "update performance.performance_cycle set "
                        + domainSet
                        + "current_node_code=:targetNode,status=:status,"
                        + "result_summary=coalesce(:summary,result_summary),"
                        + "version_no=version_no+1,updated_by=:actorId,updated_at=now() "
                        + "where tenant_id=:tenantId and id=:recordId and version_no=:expectedVersion "
                        + "and current_node_code=:expectedNode "
                        + "and employee_event_type='P011_PERFORMANCE' and not is_deleted",
                parameters);
    }

    @Override
    public Optional<Phase11Record> find(Phase11Process process, UUID tenantId, UUID recordId) {
        if (process != Phase11Process.P011) {
            throw unsupported(process);
        }
        return jdbc
                .query(
                        selectPerformance("and p.id=:recordId"),
                        params("tenantId", tenantId, "recordId", recordId),
                        this::mapPerformance)
                .stream()
                .findFirst();
    }

    @Override
    public List<Phase11Record> list(Phase11Process process, UUID tenantId) {
        if (process != Phase11Process.P011) {
            throw unsupported(process);
        }
        return jdbc.query(
                selectPerformance("order by p.created_at desc,p.id desc"),
                params("tenantId", tenantId),
                this::mapPerformance);
    }

    @Override
    public int submitPerformanceScore(
            UUID tenantId,
            UUID cycleId,
            int expectedVersion,
            String scoreType,
            long score1000,
            String evidenceSummary,
            UUID actorId) {
        String column =
                switch (scoreType) {
                    case "EMPLOYEE" -> "employee_score_1000";
                    case "SUPERVISOR" -> "supervisor_score_1000";
                    case "AUTHORITATIVE" -> "authoritative_score_1000";
                    case "CALIBRATED" -> "calibrated_score_1000";
                    default -> throw new ProcessRejectedException("P011 unsupported score type");
                };
        MapSqlParameterSource parameters = params(
                        "id", UUID.randomUUID(),
                        "tenantId", tenantId,
                        "cycleId", cycleId,
                        "scoreType", scoreType,
                        "score1000", score1000,
                        "evidenceSummary", evidenceSummary,
                        "actorId", actorId,
                        "expectedVersion", expectedVersion)
                .addValue("sourceFactKey", cycleId + ":" + scoreType)
                .addValue("requiredNode", "CALIBRATED".equals(scoreType) ? "S07" : "S05");
        int inserted = jdbc.update(
                """
                insert into performance.performance_score_entry(
                    id,tenant_id,cycle_id,score_type,score_1000,source_fact_key,
                    evidence_summary,submitted_by,created_by,updated_by)
                values(
                    :id,:tenantId,:cycleId,:scoreType,:score1000,:sourceFactKey,
                    :evidenceSummary,:actorId,:actorId,:actorId)
                on conflict (tenant_id,cycle_id,score_type) where not is_deleted do nothing
                """,
                parameters);
        if (inserted != 1) {
            throw new ProcessRejectedException("P011 score source already exists and cannot be overwritten");
        }
        return jdbc.update(
                "update performance.performance_cycle set "
                        + column
                        + "=:score1000,version_no=version_no+1,updated_by=:actorId,updated_at=now() "
                        + "where tenant_id=:tenantId and id=:cycleId and version_no=:expectedVersion "
                        + "and current_node_code=:requiredNode "
                        + "and employee_event_type='P011_PERFORMANCE' and not is_deleted",
                parameters);
    }

    @Override
    public PerformanceScores performanceScores(UUID tenantId, UUID cycleId) {
        return jdbc.queryForObject(
                """
                select employee_score_1000,supervisor_score_1000,
                       authoritative_score_1000,calibrated_score_1000
                from performance.performance_cycle
                where tenant_id=:tenantId and id=:cycleId
                  and employee_event_type='P011_PERFORMANCE' and not is_deleted
                """,
                params("tenantId", tenantId, "cycleId", cycleId),
                (rs, rowNum) -> new PerformanceScores(
                        nullableLong(rs, "employee_score_1000"),
                        nullableLong(rs, "supervisor_score_1000"),
                        nullableLong(rs, "authoritative_score_1000"),
                        nullableLong(rs, "calibrated_score_1000")));
    }

    private String selectPerformance(String suffix) {
        return """
                select p.id,p.tenant_id,p.business_no,p.workflow_instance_id,
                       wi.instance_no workflow_instance_no,p.current_node_code,p.status,p.version_no,
                       p.subject,p.reason,p.priority,p.risk_level,p.owner_center_id,p.owner_employee_id,
                       p.business_date,p.fact_occurred_at,p.fact_summary,p.result_summary,
                       p.created_at,p.updated_at,p.closed_at,
                       jsonb_build_object(
                         'contentVersion',p.content_version,
                         'periodNo',p.period_or_course_no,
                         'goalSummary',p.goal_summary,
                         'employeeConfirmedAt',p.employee_confirmed_at,
                         'coachingSummary',p.coaching_summary,
                         'authoritativeDataSummary',p.authoritative_data_summary,
                         'employeeScore1000',p.employee_score_1000,
                         'supervisorScore1000',p.supervisor_score_1000,
                         'authoritativeScore1000',p.authoritative_score_1000,
                         'calculatedScore1000',p.score_1000,
                         'calibratedScore1000',p.calibrated_score_1000,
                         'feedbackConfirmedAt',p.feedback_confirmed_at,
                         'appealStatus',p.appeal_status,
                         'appealReason',p.appeal_reason,
                         'appealReviewerId',p.appeal_reviewer_id,
                         'appealDecision',p.appeal_decision,
                         'impactExecutedAt',p.impact_executed_at,
                         'archivedAt',p.archived_at,
                         'scoreFacts',coalesce((
                           select jsonb_agg(jsonb_build_object(
                             'scoreType',s.score_type,'score1000',s.score_1000,
                             'evidenceSummary',s.evidence_summary,'submittedBy',s.submitted_by,
                             'submittedAt',s.created_at) order by s.created_at,s.id)
                           from performance.performance_score_entry s
                           where s.tenant_id=p.tenant_id and s.cycle_id=p.id and not s.is_deleted
                         ),'[]'::jsonb)
                       ) details
                from performance.performance_cycle p
                left join workflow.wf_instance wi
                  on wi.tenant_id=p.tenant_id and wi.id=p.workflow_instance_id and not wi.is_deleted
                where p.tenant_id=:tenantId
                  and p.employee_event_type='P011_PERFORMANCE' and not p.is_deleted
                """
                + suffix;
    }

    private Phase11Record mapPerformance(ResultSet rs, int rowNum) throws SQLException {
        return new Phase11Record(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                Phase11Process.P011.code(),
                rs.getString("business_no"),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("workflow_instance_no"),
                rs.getString("current_node_code"),
                rs.getString("status"),
                rs.getInt("version_no"),
                rs.getString("subject"),
                rs.getString("reason"),
                rs.getString("priority"),
                rs.getString("risk_level"),
                rs.getObject("owner_center_id", UUID.class),
                rs.getObject("owner_employee_id", UUID.class),
                rs.getObject("business_date", LocalDate.class),
                instant(rs, "fact_occurred_at"),
                rs.getString("fact_summary"),
                rs.getString("result_summary"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "closed_at"),
                json(rs.getString("details")));
    }

    private JsonNode json(String value) {
        try {
            return value == null ? mapper.nullNode() : mapper.readTree(value);
        } catch (Exception exception) {
            throw new ProcessRejectedException("PHASE-11 database JSON projection is invalid", exception);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new SQLException("Unsupported timestamp value for " + column + ": " + value.getClass());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) {
            source.addValue(String.valueOf(values[index]), values[index + 1]);
        }
        return source;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw new ProcessRejectedException(operation + " expected one row but changed " + rows);
        }
    }

    private static ProcessRejectedException unsupported(Phase11Process process) {
        return new ProcessRejectedException(process.code() + " repository checkpoint is not implemented yet");
    }
}
