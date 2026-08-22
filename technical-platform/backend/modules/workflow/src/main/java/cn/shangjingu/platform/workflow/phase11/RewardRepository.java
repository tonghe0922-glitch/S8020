package cn.shangjingu.platform.workflow.phase11;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RewardRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RewardRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean sourceFactAvailable(UUID tenantId, String sourceFactKey) {
        Boolean exists = jdbc.queryForObject(
                """
                select exists(
                  select 1 from reward.reward_case
                  where tenant_id=:tenantId and source_fact_key=:sourceFactKey and not is_deleted)
                """,
                params("tenantId", tenantId, "sourceFactKey", sourceFactKey),
                Boolean.class);
        return !Boolean.TRUE.equals(exists);
    }

    public boolean paidFinanceReference(UUID tenantId, UUID referenceId, BigDecimal expectedAmount) {
        if (referenceId == null) {
            return false;
        }
        Boolean ready = jdbc.queryForObject(
                """
                select (
                  exists(
                    select 1 from finance.budget_request f
                    where f.tenant_id=:tenantId and f.id=:referenceId and not f.is_deleted
                      and lower(coalesce(f.payment_status,'')) in
                          ('paid','settled','completed','已支付','已结算')
                      and lower(coalesce(f.invoice_verification,'not_required')) in
                          ('verified','pass','not_required','已验真','无需票据')
                      and coalesce(f.actual_amount,f.approved_amount,f.requested_amount,0) >= :amount)
                  or exists(
                    select 1 from finance.expense_claim f
                    where f.tenant_id=:tenantId and f.id=:referenceId and not f.is_deleted
                      and lower(coalesce(f.payment_status,'')) in
                          ('paid','settled','completed','已支付','已结算')
                      and lower(coalesce(f.invoice_verification,'not_required')) in
                          ('verified','pass','not_required','已验真','无需票据')
                      and coalesce(f.actual_amount,f.approved_amount,f.requested_amount,0) >= :amount)
                )
                """,
                params(
                        "tenantId", tenantId,
                        "referenceId", referenceId,
                        "amount", expectedAmount == null ? BigDecimal.ZERO : expectedAmount),
                Boolean.class);
        return Boolean.TRUE.equals(ready);
    }

    public boolean executionEffectAbsent(UUID tenantId, UUID rewardId) {
        Boolean exists = jdbc.queryForObject(
                """
                select exists(
                  select 1 from reward.reward_case r
                  where r.tenant_id=:tenantId and r.id=:rewardId
                    and r.point_effect_id is not null and not r.is_deleted
                  union all
                  select 1 from reward.point_transaction p
                  where p.tenant_id=:tenantId and p.source_reward_case_id=:rewardId
                    and not p.is_deleted)
                """,
                params("tenantId", tenantId, "rewardId", rewardId),
                Boolean.class);
        return !Boolean.TRUE.equals(exists);
    }

    public void insert(Phase11Record record, RewardService.CreateCommand command, UUID actorId) {
        int rows = jdbc.update(
                """
                insert into reward.reward_case(
                  id,tenant_id,business_no,workflow_instance_id,status,current_node_code,version_no,
                  created_by,updated_by,source_channel,business_date,subject,reason,priority,risk_level,
                  owner_center_id,owner_employee_id,benefit_amount,comp_grade_impact,
                  employee_event_type,fact_occurred_at,fact_summary,impact_effective_date,
                  impact_level,points_delta,source_fact_key,content_version,period_no)
                values(
                  :id,:tenantId,:businessNo,null,:status,'S01',0,
                  :actorId,:actorId,'PORTAL',:businessDate,:subject,:reason,:priority,:riskLevel,
                  :ownerCenterId,:ownerEmployeeId,:benefitAmount,:compGradeImpact,
                  :employeeEventType,:factOccurredAt,:factSummary,:impactEffectiveDate,
                  :impactLevel,:pointsDelta,:sourceFactKey,:contentVersion,:periodNo)
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
                        "benefitAmount", amount(command.benefitAmount()),
                        "compGradeImpact", trimToNull(command.compGradeImpact()),
                        "employeeEventType", normalized(command.employeeEventType(), "P013_REWARD"),
                        "factOccurredAt", timestamp(record.factOccurredAt()),
                        "factSummary", record.factSummary(),
                        "impactEffectiveDate", command.impactEffectiveDate(),
                        "impactLevel", command.impactLevel().trim(),
                        "pointsDelta", command.pointsDelta(),
                        "sourceFactKey", command.sourceFactKey().trim(),
                        "contentVersion", command.contentVersion().trim(),
                        "periodNo", command.periodNo().trim()));
        requireSingle(rows, "canonical reward insert");
    }

    public int bindWorkflow(
            UUID tenantId,
            UUID rewardId,
            int expectedVersion,
            UUID workflowInstanceId,
            String nodeCode,
            String status,
            UUID actorId) {
        return jdbc.update(
                """
                update reward.reward_case
                   set workflow_instance_id=:workflowInstanceId,current_node_code=:nodeCode,
                       status=:status,version_no=version_no+1,updated_by=:actorId,updated_at=now()
                 where tenant_id=:tenantId and id=:rewardId
                   and version_no=:expectedVersion and not is_deleted
                """,
                params(
                        "workflowInstanceId", workflowInstanceId,
                        "nodeCode", nodeCode,
                        "status", status,
                        "actorId", actorId,
                        "tenantId", tenantId,
                        "rewardId", rewardId,
                        "expectedVersion", expectedVersion));
    }

    public UUID createPointEffect(Phase11Record current, String summary, UUID actorId) {
        long points = current.details().path("pointsDelta").asLong(0L);
        if (points == 0L) {
            return null;
        }
        UUID proposedId = UUID.randomUUID();
        MapSqlParameterSource parameters = params(
                "id", proposedId,
                "tenantId", current.tenantId(),
                "businessNo", current.businessNo(),
                "actorId", actorId,
                "businessDate", current.businessDate(),
                "subject", current.subject(),
                "reason", current.reason(),
                "priority", current.priority(),
                "riskLevel", current.riskLevel(),
                "ownerCenterId", current.ownerCenterId(),
                "ownerEmployeeId", current.ownerEmployeeId(),
                "summary", summary,
                "compGradeImpact", text(current.details(), "compGradeImpact"),
                "factOccurredAt", timestamp(current.factOccurredAt()),
                "factSummary", current.factSummary(),
                "impactEffectiveDate", date(current.details(), "impactEffectiveDate"),
                "impactLevel", text(current.details(), "impactLevel"),
                "pointsDelta", points,
                "rewardId", current.id(),
                "sourceFactKey", "P013:" + current.id(),
                "contentVersion", text(current.details(), "contentVersion"),
                "periodNo", text(current.details(), "periodNo"));
        jdbc.update(
                """
                insert into reward.point_transaction(
                  id,tenant_id,business_no,workflow_instance_id,status,current_node_code,version_no,
                  created_by,updated_by,source_channel,business_date,subject,reason,priority,risk_level,
                  owner_center_id,owner_employee_id,actual_start_at,actual_end_at,result_summary,closed_at,
                  actual_amount,benefit_amount,change_action,change_reason,comp_grade_impact,
                  cost_center_id,currency,employee_event_type,fact_occurred_at,fact_summary,
                  impact_effective_date,impact_level,known_impact,points_delta,
                  source_fact_key,source_reward_case_id,content_version,period_no)
                values(
                  :id,:tenantId,left(:businessNo || '-PTS',64),null,'已入账','END',0,
                  :actorId,:actorId,'PHASE11_EFFECT',:businessDate,:subject,:reason,:priority,:riskLevel,
                  :ownerCenterId,:ownerEmployeeId,now(),now(),:summary,now(),
                  0,0,'REWARD_POST',:summary,:compGradeImpact,
                  'NON_FINANCIAL','POINT','P013_REWARD_EFFECT',:factOccurredAt,:factSummary,
                  :impactEffectiveDate,:impactLevel,'P013 exactly-once reward impact',:pointsDelta,
                  :sourceFactKey,:rewardId,:contentVersion,:periodNo)
                on conflict do nothing
                """,
                parameters);
        UUID effect = jdbc.queryForObject(
                """
                select id from reward.point_transaction
                where tenant_id=:tenantId and source_reward_case_id=:rewardId and not is_deleted
                """,
                parameters,
                UUID.class);
        if (effect == null) {
            throw rejected("reward point effect was not persisted");
        }
        return effect;
    }

    public int advance(
            Phase11Record current,
            String action,
            String targetNode,
            String status,
            RewardService.ActionCommand command,
            UUID pointEffectId,
            UUID actorId) {
        MapSqlParameterSource parameters = params(
                "tenantId", current.tenantId(),
                "rewardId", current.id(),
                "expectedVersion", command.expectedVersion(),
                "expectedNode", current.currentNodeCode(),
                "targetNode", targetNode,
                "status", status,
                "actorId", actorId,
                "summary", trimToNull(command.summary()),
                "decision", trimToNull(command.decision()),
                "financeReferenceId", command.financeReferenceId(),
                "pointEffectId", pointEffectId,
                "receiptReference", trimToNull(command.receiptReference()));
        String domainSet =
                switch (action) {
                    case "VERIFY_EVIDENCE" -> "evidence_verified_at=coalesce(evidence_verified_at,now()),";
                    case "RECOMMEND_REWARD" -> "recommendation_summary=:summary,";
                    case "APPROVE_REWARD" -> "approval_decision=:decision,approved_at=coalesce(approved_at,now()),";
                    case "CHECK_DUPLICATE_IMPACT" -> "duplicate_checked_at=coalesce(duplicate_checked_at,now()),";
                    case "EXECUTE_REWARD" -> "finance_reference_id=coalesce(:financeReferenceId,finance_reference_id),"
                            + "point_effect_id=coalesce(:pointEffectId,point_effect_id),"
                            + "reward_executed_at=coalesce(reward_executed_at,now()),";
                    case "NOTIFY_EMPLOYEE" -> "employee_notified_at=coalesce(employee_notified_at,now()),";
                    case "RECORD_RECEIPTS" -> "receipt_reference=:receiptReference,"
                            + "receipts_recorded_at=coalesce(receipts_recorded_at,now()),";
                    case "ARCHIVE" -> "archived_at=coalesce(archived_at,now()),"
                            + "closed_at=coalesce(closed_at,now()),"
                            + "actual_end_at=coalesce(actual_end_at,now()),";
                    default -> "";
                };
        return jdbc.update(
                "update reward.reward_case set "
                        + domainSet
                        + "current_node_code=:targetNode,status=:status,"
                        + "result_summary=coalesce(:summary,result_summary),"
                        + "version_no=version_no+1,updated_by=:actorId,updated_at=now() "
                        + "where tenant_id=:tenantId and id=:rewardId "
                        + "and version_no=:expectedVersion and current_node_code=:expectedNode "
                        + "and not is_deleted",
                parameters);
    }

    public Optional<Phase11Record> find(UUID tenantId, UUID rewardId) {
        return jdbc
                .query(
                        selectSql("and r.id=:rewardId"),
                        params("tenantId", tenantId, "rewardId", rewardId),
                        this::mapRecord)
                .stream()
                .findFirst();
    }

    public List<Phase11Record> list(UUID tenantId) {
        return jdbc.query(
                selectSql("order by r.created_at desc,r.id desc"), params("tenantId", tenantId), this::mapRecord);
    }

    private String selectSql(String suffix) {
        return """
                select r.id,r.tenant_id,r.business_no,r.workflow_instance_id,
                       wi.instance_no workflow_instance_no,r.current_node_code,r.status,r.version_no,
                       r.subject,r.reason,r.priority,r.risk_level,r.owner_center_id,r.owner_employee_id,
                       r.business_date,r.fact_occurred_at,r.fact_summary,r.result_summary,
                       r.created_at,r.updated_at,r.closed_at,
                       jsonb_build_object(
                         'sourceFactKey',r.source_fact_key,
                         'contentVersion',r.content_version,'periodNo',r.period_no,
                         'benefitAmount',r.benefit_amount,'pointsDelta',r.points_delta,
                         'compGradeImpact',r.comp_grade_impact,
                         'impactLevel',r.impact_level,'impactEffectiveDate',r.impact_effective_date,
                         'evidenceVerifiedAt',r.evidence_verified_at,
                         'recommendationSummary',r.recommendation_summary,
                         'approvalDecision',r.approval_decision,'approvedAt',r.approved_at,
                         'duplicateCheckedAt',r.duplicate_checked_at,
                         'financeReferenceId',r.finance_reference_id,
                         'pointEffectId',r.point_effect_id,
                         'rewardExecutedAt',r.reward_executed_at,
                         'employeeNotifiedAt',r.employee_notified_at,
                         'receiptReference',r.receipt_reference,
                         'receiptsRecordedAt',r.receipts_recorded_at,
                         'archivedAt',r.archived_at) details
                  from reward.reward_case r
                  left join workflow.wf_instance wi
                    on wi.tenant_id=r.tenant_id and wi.id=r.workflow_instance_id and not wi.is_deleted
                 where r.tenant_id=:tenantId and not r.is_deleted
                """
                + suffix;
    }

    private Phase11Record mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new Phase11Record(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                Phase11Process.P013.code(),
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
                json(rs, "details"));
    }

    private JsonNode json(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        try {
            return value == null ? mapper.createObjectNode() : mapper.readTree(value);
        } catch (Exception exception) {
            throw new SQLException("P013 projection JSON cannot be parsed", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return null;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String text(JsonNode details, String field) {
        String value = details.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static LocalDate date(JsonNode details, String field) {
        String value = text(details, field);
        return value == null ? null : LocalDate.parse(value);
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
            throw rejected(operation + " affected " + rows + " rows");
        }
    }

    private static ProcessRejectedException rejected(String message) {
        return new ProcessRejectedException("P013 " + message);
    }
}
