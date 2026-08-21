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
public class PromotionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PromotionRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Eligibility authoritativeEligibility(
            UUID tenantId, UUID centerId, UUID employeeId, UUID performanceCycleId) {
        return jdbc.query(
                        """
                        select case when p.current_node_code='END' then 'CLOSED' else 'OPEN' end fsm_state,
                               case when p.current_node_code='END' then 'FINISHED' else 'IN_PROGRESS' end timebox_state,
                               case when p.current_node_code='END' then 'QA_PASS' else 'QA_PENDING' end qa_state,
                               (select count(*)
                                  from performance.performance_score_entry s
                                 where s.tenant_id=p.tenant_id and s.cycle_id=p.id and not s.is_deleted)
                                   review_facet_count,
                               coalesce(p.calibrated_score_1000,p.score_1000) weighted_review_score
                          from performance.performance_cycle p
                         where p.tenant_id=:tenantId and p.id=:cycleId
                           and p.owner_center_id=:centerId and p.owner_employee_id=:employeeId
                           and p.employee_event_type='P011_PERFORMANCE' and not p.is_deleted
                        """,
                        params(
                                "tenantId", tenantId,
                                "cycleId", performanceCycleId,
                                "centerId", centerId,
                                "employeeId", employeeId),
                        (rs, rowNum) -> new Eligibility(
                                rs.getString("fsm_state"),
                                rs.getString("timebox_state"),
                                rs.getString("qa_state"),
                                rs.getInt("review_facet_count"),
                                nullableLong(rs, "weighted_review_score")))
                .stream()
                .findFirst()
                .orElseThrow(() -> rejected(
                        "source P011 performance cycle is missing or outside candidate scope"));
    }

    public boolean activeTargetPosition(UUID tenantId, UUID centerId, UUID positionId) {
        Boolean value = jdbc.queryForObject(
                """
                select exists(
                  select 1 from org.position
                   where tenant_id=:tenantId and id=:positionId and org_id=:centerId
                     and status='ACTIVE' and not is_deleted)
                """,
                params(
                        "tenantId", tenantId,
                        "positionId", positionId,
                        "centerId", centerId),
                Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    public boolean activeCurrentAppointment(
            UUID tenantId, UUID employeeId, UUID positionId) {
        if (positionId == null) {
            return true;
        }
        Boolean value = jdbc.queryForObject(
                """
                select exists(
                  select 1 from org.employee_position
                   where tenant_id=:tenantId and employee_id=:employeeId
                     and position_id=:positionId and status='ACTIVE' and not is_deleted)
                """,
                params(
                        "tenantId", tenantId,
                        "employeeId", employeeId,
                        "positionId", positionId),
                Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    public void insert(
            Phase11Record record,
            PromotionService.CreateCommand command,
            Eligibility eligibility,
            UUID actorId) {
        int rows = jdbc.update(
                """
                insert into hr.promotion_request(
                  id,tenant_id,business_no,workflow_instance_id,status,current_node_code,version_no,
                  created_by,updated_by,source_channel,business_date,subject,reason,priority,risk_level,
                  owner_center_id,owner_employee_id,fact_occurred_at,fact_summary,
                  actual_effective_date,employment_type,period_or_course_no,person_name,person_no,
                  planned_effective_date,target_job_id,
                  source_performance_cycle_id,current_position_id,target_position_id,
                  fsm_state,timebox_state,qa_state,review_facet_count,weighted_review_score,
                  promotion_threshold_score,content_version,nomination_summary,ceo_mode)
                select
                  :id,:tenantId,:businessNo,null,:status,'S01',0,
                  :actorId,:actorId,'PORTAL',:businessDate,:subject,:reason,:priority,:riskLevel,
                  :ownerCenterId,:ownerEmployeeId,:factOccurredAt,:factSummary,
                  null,:employmentType,:periodNo,e.person_name,e.employee_no,
                  :plannedEffectiveDate,left(pos.position_code,32),
                  :sourcePerformanceCycleId,:currentPositionId,:targetPositionId,
                  :fsmState,:timeboxState,:qaState,:reviewFacetCount,:weightedReviewScore,
                  :promotionThresholdScore,:contentVersion,:nominationSummary,:ceoMode
                from org.employee e
                join org.position pos
                  on pos.tenant_id=e.tenant_id and pos.id=:targetPositionId
                 and pos.org_id=:ownerCenterId and pos.status='ACTIVE' and not pos.is_deleted
                where e.tenant_id=:tenantId and e.id=:ownerEmployeeId
                  and e.employment_status='ACTIVE' and not e.is_deleted
                """,
                params(
                        "id", record.id(),
                        "tenantId", record.tenantId(),
                        "businessNo", record.businessNo(),
                        "status", record.status(),
                        "actorId", actorId,
                        "businessDate", record.businessDate(),
                        "subject", record.subject(),
                        "reason", record.reason(),
                        "priority", record.priority(),
                        "riskLevel", record.riskLevel(),
                        "ownerCenterId", record.ownerCenterId(),
                        "ownerEmployeeId", record.ownerEmployeeId(),
                        "factOccurredAt", timestamp(record.factOccurredAt()),
                        "factSummary", record.factSummary(),
                        "employmentType", normalized(command.employmentType(), "PROMOTION"),
                        "periodNo", command.periodNo(),
                        "plannedEffectiveDate", command.appointmentEffectiveDate(),
                        "sourcePerformanceCycleId", command.sourcePerformanceCycleId(),
                        "currentPositionId", command.currentPositionId(),
                        "targetPositionId", command.targetPositionId(),
                        "fsmState", eligibility.fsmState(),
                        "timeboxState", eligibility.timeboxState(),
                        "qaState", eligibility.qaState(),
                        "reviewFacetCount", eligibility.reviewFacetCount(),
                        "weightedReviewScore", eligibility.requiredScore(),
                        "promotionThresholdScore", command.promotionThresholdScore(),
                        "contentVersion", command.contentVersion(),
                        "nominationSummary", command.nominationSummary(),
                        "ceoMode", Boolean.TRUE.equals(command.ceoMode())));
        requireSingle(rows, "canonical promotion insert");
    }

    public int bindWorkflow(
            UUID tenantId,
            UUID promotionId,
            int expectedVersion,
            UUID workflowInstanceId,
            String nodeCode,
            String status,
            UUID actorId) {
        return jdbc.update(
                """
                update hr.promotion_request
                   set workflow_instance_id=:workflowInstanceId,current_node_code=:nodeCode,
                       status=:status,version_no=version_no+1,updated_by=:actorId,updated_at=now()
                 where tenant_id=:tenantId and id=:promotionId
                   and version_no=:expectedVersion and not is_deleted
                """,
                params(
                        "workflowInstanceId", workflowInstanceId,
                        "nodeCode", nodeCode,
                        "status", status,
                        "actorId", actorId,
                        "tenantId", tenantId,
                        "promotionId", promotionId,
                        "expectedVersion", expectedVersion));
    }

    public int advance(
            Phase11Record current,
            String action,
            String targetNode,
            String status,
            PromotionService.ActionCommand command,
            UUID appointmentEffectId,
            UUID actorId) {
        MapSqlParameterSource parameters = params(
                        "tenantId", current.tenantId(),
                        "promotionId", current.id(),
                        "expectedVersion", command.expectedVersion(),
                        "expectedNode", current.currentNodeCode(),
                        "targetNode", targetNode,
                        "status", status,
                        "actorId", actorId,
                        "summary", trimToNull(command.summary()),
                        "decision", trimToNull(command.decision()),
                        "effectiveDate", effectiveDate(current, command),
                        "appointmentEffectId", appointmentEffectId)
                .addValue("targetPositionId", uuid(current.details(), "targetPositionId"));
        String domainSet = switch (action) {
            case "PASS_ELIGIBILITY" ->
                    "eligibility_verified_at=coalesce(eligibility_verified_at,now()),";
            case "SUBMIT_ASSESSMENT" -> "assessment_summary=:summary,";
            case "VERIFY_POSITION_BUDGET" ->
                    "position_budget_verified_at=coalesce(position_budget_verified_at,now()),";
            case "COMPLETE_REVIEW" -> "review_summary=:summary,";
            case "APPROVE_PROMOTION" ->
                    "approval_decision=:decision,approved_at=coalesce(approved_at,now()),";
            case "COMPLETE_NOTICE" ->
                    "notice_completed_at=coalesce(notice_completed_at,now()),";
            case "CONFIRM_APPOINTMENT" ->
                    "employee_confirmed_at=coalesce(employee_confirmed_at,now()),";
            case "COMPLETE_VALIDATION" ->
                    "validation_completed_at=coalesce(validation_completed_at,now()),"
                            + "planned_effective_date=:effectiveDate,";
            case "ACTIVATE_APPOINTMENT" ->
                    "appointment_activated_at=coalesce(appointment_activated_at,now()),"
                            + "appointment_effect_id=:appointmentEffectId,"
                            + "appointment_position_id=:targetPositionId,"
                            + "actual_effective_date=:effectiveDate,"
                            + "closed_at=coalesce(closed_at,now()),"
                            + "actual_end_at=coalesce(actual_end_at,now()),";
            default -> "";
        };
        return jdbc.update(
                "update hr.promotion_request set "
                        + domainSet
                        + "current_node_code=:targetNode,status=:status,"
                        + "result_summary=coalesce(:summary,result_summary),"
                        + "version_no=version_no+1,updated_by=:actorId,updated_at=now() "
                        + "where tenant_id=:tenantId and id=:promotionId "
                        + "and version_no=:expectedVersion and current_node_code=:expectedNode "
                        + "and not is_deleted",
                parameters);
    }

    public UUID activateAppointment(
            Phase11Record current,
            PromotionService.ActionCommand command,
            UUID actorId) {
        UUID targetPositionId = uuid(current.details(), "targetPositionId");
        LocalDate effectiveDate = effectiveDate(current, command);
        MapSqlParameterSource parameters = params(
                "id", UUID.randomUUID(),
                "tenantId", current.tenantId(),
                "actorId", actorId,
                "employeeId", current.ownerEmployeeId(),
                "positionId", targetPositionId,
                "orgId", current.ownerCenterId(),
                "effectiveDate", effectiveDate,
                "promotionId", current.id());
        jdbc.update(
                """
                insert into org.employee_position(
                  id,tenant_id,created_by,updated_by,employee_id,position_id,org_id,
                  is_primary,effective_start_date,status,source_promotion_request_id)
                values(
                  :id,:tenantId,:actorId,:actorId,:employeeId,:positionId,:orgId,
                  true,:effectiveDate,'ACTIVE',:promotionId)
                on conflict (tenant_id,source_promotion_request_id)
                  where source_promotion_request_id is not null do nothing
                """,
                parameters);
        AppointmentEffect effect = jdbc.queryForObject(
                """
                select id,employee_id,position_id,org_id
                  from org.employee_position
                 where tenant_id=:tenantId and source_promotion_request_id=:promotionId
                   and not is_deleted
                """,
                parameters,
                (rs, rowNum) -> new AppointmentEffect(
                        rs.getObject("id", UUID.class),
                        rs.getObject("employee_id", UUID.class),
                        rs.getObject("position_id", UUID.class),
                        rs.getObject("org_id", UUID.class)));
        if (effect == null
                || !current.ownerEmployeeId().equals(effect.employeeId())
                || !targetPositionId.equals(effect.positionId())
                || !current.ownerCenterId().equals(effect.orgId())) {
            throw rejected("appointment effect conflicts with promotion request");
        }
        int employeeRows = jdbc.update(
                """
                update org.employee
                   set primary_position_id=:positionId,updated_by=:actorId,updated_at=now()
                 where tenant_id=:tenantId and id=:employeeId
                   and employment_status='ACTIVE' and not is_deleted
                """,
                parameters);
        requireSingle(employeeRows, "candidate primary position activation");
        return effect.id();
    }

    public Optional<Phase11Record> find(UUID tenantId, UUID promotionId) {
        return jdbc.query(
                        selectSql("and p.id=:promotionId"),
                        params("tenantId", tenantId, "promotionId", promotionId),
                        this::mapRecord)
                .stream()
                .findFirst();
    }

    public List<Phase11Record> list(UUID tenantId) {
        return jdbc.query(
                selectSql("order by p.created_at desc,p.id desc"),
                params("tenantId", tenantId),
                this::mapRecord);
    }

    private String selectSql(String suffix) {
        return """
                select p.id,p.tenant_id,p.business_no,p.workflow_instance_id,
                       wi.instance_no workflow_instance_no,p.current_node_code,p.status,p.version_no,
                       p.subject,p.reason,p.priority,p.risk_level,p.owner_center_id,p.owner_employee_id,
                       p.business_date,p.fact_occurred_at,p.fact_summary,p.result_summary,
                       p.created_at,p.updated_at,p.closed_at,
                       jsonb_build_object(
                         'sourcePerformanceCycleId',p.source_performance_cycle_id,
                         'currentPositionId',p.current_position_id,
                         'targetPositionId',p.target_position_id,
                         'fsmState',p.fsm_state,'timeboxState',p.timebox_state,'qaState',p.qa_state,
                         'reviewFacetCount',p.review_facet_count,
                         'weightedReviewScore',p.weighted_review_score,
                         'promotionThresholdScore',p.promotion_threshold_score,
                         'contentVersion',p.content_version,'periodNo',p.period_or_course_no,
                         'nominationSummary',p.nomination_summary,
                         'eligibilityVerifiedAt',p.eligibility_verified_at,
                         'assessmentSummary',p.assessment_summary,
                         'positionBudgetVerifiedAt',p.position_budget_verified_at,
                         'reviewSummary',p.review_summary,'approvalDecision',p.approval_decision,
                         'approvedAt',p.approved_at,'noticeCompletedAt',p.notice_completed_at,
                         'employeeConfirmedAt',p.employee_confirmed_at,
                         'validationCompletedAt',p.validation_completed_at,
                         'appointmentEffectiveDate',p.planned_effective_date,
                         'appointmentActivatedAt',p.appointment_activated_at,
                         'appointmentEffectId',p.appointment_effect_id,
                         'appointmentPositionId',p.appointment_position_id,'ceoMode',p.ceo_mode) details
                  from hr.promotion_request p
                  left join workflow.wf_instance wi
                    on wi.tenant_id=p.tenant_id and wi.id=p.workflow_instance_id and not wi.is_deleted
                 where p.tenant_id=:tenantId and not p.is_deleted
                """ + suffix;
    }

    private Phase11Record mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new Phase11Record(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                Phase11Process.P012.code(),
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
            throw new ProcessRejectedException("P012 database JSON projection is invalid", exception);
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

    private static LocalDate effectiveDate(
            Phase11Record current, PromotionService.ActionCommand command) {
        if (command.appointmentEffectiveDate() != null) {
            return command.appointmentEffectiveDate();
        }
        String value = current.details().path("appointmentEffectiveDate").asText(null);
        if (value == null || value.isBlank()) {
            throw rejected("appointmentEffectiveDate is required before activation");
        }
        return LocalDate.parse(value);
    }

    private static UUID uuid(JsonNode details, String field) {
        String value = details.path(field).asText(null);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) {
            source.addValue(String.valueOf(values[index]), values[index + 1]);
        }
        return source;
    }

    private static void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw rejected(operation + " expected one row but changed " + rows);
        }
    }

    private static ProcessRejectedException rejected(String message) {
        return new ProcessRejectedException("P012 " + message);
    }

    public record Eligibility(
            String fsmState,
            String timeboxState,
            String qaState,
            int reviewFacetCount,
            Long weightedReviewScore) {
        public long requiredScore() {
            if (weightedReviewScore == null) {
                throw rejected("authoritative P011 score is required");
            }
            return weightedReviewScore;
        }
    }

    private record AppointmentEffect(UUID id, UUID employeeId, UUID positionId, UUID orgId) {}
}
