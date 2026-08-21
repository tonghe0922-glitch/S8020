package cn.shangjingu.platform.workflow.phase11;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PointLedgerRepository {
    private static final String EVENT_TYPE = "P015_POINTS";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PointLedgerRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void reserveSource(UUID tenantId, String sourceFactKey, UUID caseId, UUID actorId) {
        try {
            jdbc.update("""
                    insert into reward.point_source_guard(
                      id,tenant_id,source_fact_key,point_case_id,created_by,created_at)
                    values(gen_random_uuid(),:tenantId,:sourceFactKey,:caseId,:actorId,now())
                    """, params("tenantId", tenantId, "sourceFactKey", sourceFactKey,
                            "caseId", caseId, "actorId", actorId));
        } catch (DuplicateKeyException exception) {
            throw rejected("source event already registered");
        }
    }

    public boolean sourceReservedFor(UUID tenantId, String sourceFactKey, UUID caseId) {
        Boolean result = jdbc.queryForObject("""
                select exists(select 1 from reward.point_source_guard
                  where tenant_id=:tenantId and source_fact_key=:sourceFactKey and point_case_id=:caseId)
                """, params("tenantId", tenantId, "sourceFactKey", sourceFactKey, "caseId", caseId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public int mergeWorkflowContext(UUID tenantId, UUID instanceId, String expectedNode, JsonNode patch, UUID actorId) {
        return jdbc.update("""
                update workflow.wf_instance
                   set context_snapshot=coalesce(context_snapshot,'{}'::jsonb) || cast(:patch as jsonb),
                       updated_by=:actorId,updated_at=now()
                 where tenant_id=:tenantId and id=:instanceId and current_node_code=:expectedNode
                   and status='RUNNING' and not is_deleted
                """, params("patch", json(patch), "actorId", actorId, "tenantId", tenantId,
                        "instanceId", instanceId, "expectedNode", expectedNode));
    }

    public Optional<PointRule> publishedRule(
            UUID tenantId, String ruleCode, String sourceType, String pointType, Instant factOccurredAt) {
        return jdbc.query("""
                select id,rule_code,version_code,source_type,point_type,points_delta,review_threshold_abs,
                       effective_from,effective_to
                  from reward.point_rule_version
                 where tenant_id=:tenantId and rule_code=:ruleCode and source_type=:sourceType
                   and point_type=:pointType and status='PUBLISHED' and effective_from<=:occurredAt
                   and (effective_to is null or effective_to>:occurredAt) and not is_deleted
                 order by effective_from desc,version_code desc,id desc
                 limit 1
                """, params("tenantId", tenantId, "ruleCode", ruleCode,
                        "sourceType", sourceType, "pointType", pointType,
                        "occurredAt", timestamp(factOccurredAt)),
                (rs, rowNum) -> new PointRule(
                        rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("version_code"),
                        rs.getString("source_type"), rs.getString("point_type"), rs.getLong("points_delta"),
                        nullableLong(rs, "review_threshold_abs"), instant(rs, "effective_from"), instant(rs, "effective_to")))
                .stream().findFirst();
    }

    public boolean originalPostingAbsent(UUID tenantId, UUID caseId) {
        Boolean exists = jdbc.queryForObject("""
                select exists(select 1 from reward.point_transaction
                  where tenant_id=:tenantId and id=:caseId and employee_event_type='P015_POINTS'
                    and change_action='POST' and not is_deleted)
                """, params("tenantId", tenantId, "caseId", caseId), Boolean.class);
        return !Boolean.TRUE.equals(exists);
    }

    public void insertPosting(PointLedgerView current, JsonNode context, UUID actorId) {
        int rows = jdbc.update("""
                insert into reward.point_transaction(
                  id,tenant_id,business_no,workflow_instance_id,status,version_no,created_by,created_at,
                  source_channel,business_date,subject,reason,priority,risk_level,owner_center_id,owner_employee_id,
                  result_summary,change_action,change_reason,cost_center_id,currency,employee_event_type,
                  fact_occurred_at,fact_summary,impact_level,points_delta,source_fact_key,source_type,point_type,
                  rule_code,rule_version,calculation_snapshot,risk_class,root_transaction_id,reversal_of_id)
                values(
                  :id,:tenantId,:businessNo,:workflowInstanceId,'POSTED',0,:actorId,now(),
                  'WORKFLOW',:businessDate,:subject,:reason,:priority,:riskLevel,:ownerCenterId,:ownerEmployeeId,
                  :resultSummary,'POST',:changeReason,null,null,:eventType,
                  :factOccurredAt,:factSummary,:impactLevel,:pointsDelta,:sourceFactKey,:sourceType,:pointType,
                  :ruleCode,:ruleVersion,cast(:calculationSnapshot as jsonb),:riskClass,null,null)
                """, params(
                        "id", current.id(), "tenantId", current.tenantId(), "businessNo", current.businessNo(),
                        "workflowInstanceId", current.workflowInstanceId(), "actorId", actorId,
                        "businessDate", date(context, "businessDate"), "subject", current.subject(),
                        "reason", text(context, "reason"), "priority", current.priority(),
                        "riskLevel", text(context, "riskClass"), "ownerCenterId", current.ownerCenterId(),
                        "ownerEmployeeId", current.ownerEmployeeId(), "resultSummary", text(context, "calculationSummary"),
                        "changeReason", text(context, "factSummary"), "eventType", EVENT_TYPE,
                        "factOccurredAt", timestamp(instant(context, "factOccurredAt")),
                        "factSummary", text(context, "factSummary"), "impactLevel", text(context, "impactLevel"),
                        "pointsDelta", longValue(context, "calculatedPoints"), "sourceFactKey", text(context, "sourceFactKey"),
                        "sourceType", text(context, "sourceType"), "pointType", text(context, "pointType"),
                        "ruleCode", text(context, "ruleCode"), "ruleVersion", text(context, "matchedRuleVersion"),
                        "calculationSnapshot", json(context.path("calculationSnapshot")),
                        "riskClass", text(context, "riskClass")));
        requireSingle(rows, "point posting insert");
    }

    public PointTransaction requiredOriginal(UUID tenantId, UUID caseId) {
        return jdbc.query("""
                select id,tenant_id,business_no,owner_center_id,owner_employee_id,business_date,fact_occurred_at,
                       fact_summary,impact_level,points_delta,source_fact_key,source_type,point_type,rule_code,rule_version
                  from reward.point_transaction
                 where tenant_id=:tenantId and id=:caseId and employee_event_type='P015_POINTS'
                   and change_action='POST' and not is_deleted
                """, params("tenantId", tenantId, "caseId", caseId), this::mapTransaction)
                .stream().findFirst().orElseThrow(() -> rejected("original posted transaction is missing"));
    }

    public UUID insertCorrection(
            PointTransaction original,
            String businessNo,
            String mode,
            long pointsDelta,
            String reason,
            JsonNode evidence,
            UUID workflowInstanceId,
            UUID actorId) {
        UUID id = UUID.randomUUID();
        int rows = jdbc.update("""
                insert into reward.point_transaction(
                  id,tenant_id,business_no,workflow_instance_id,status,version_no,created_by,created_at,
                  source_channel,business_date,subject,reason,priority,risk_level,owner_center_id,owner_employee_id,
                  result_summary,change_action,change_reason,cost_center_id,currency,employee_event_type,
                  fact_occurred_at,fact_summary,impact_level,points_delta,source_fact_key,source_type,point_type,
                  rule_code,rule_version,calculation_snapshot,risk_class,root_transaction_id,reversal_of_id,correction_evidence)
                values(
                  :id,:tenantId,:businessNo,:workflowInstanceId,'POSTED',0,:actorId,now(),
                  'WORKFLOW',:businessDate,:subject,:reason,'NORMAL','CONTROLLED',:ownerCenterId,:ownerEmployeeId,
                  :reason,:mode,:reason,null,null,:eventType,
                  :factOccurredAt,:factSummary,:impactLevel,:pointsDelta,null,:sourceType,:pointType,
                  :ruleCode,:ruleVersion,'{}'::jsonb,'CONTROLLED',:rootId,:reversalOfId,cast(:evidence as jsonb))
                """, params(
                        "id", id, "tenantId", original.tenantId(), "businessNo", businessNo,
                        "workflowInstanceId", workflowInstanceId, "actorId", actorId,
                        "businessDate", original.businessDate(), "subject", "P015 " + mode,
                        "reason", reason, "ownerCenterId", original.ownerCenterId(),
                        "ownerEmployeeId", original.ownerEmployeeId(), "mode", mode, "eventType", EVENT_TYPE,
                        "factOccurredAt", timestamp(Instant.now()), "factSummary", reason,
                        "impactLevel", original.impactLevel(), "pointsDelta", pointsDelta,
                        "sourceType", original.sourceType(), "pointType", original.pointType(),
                        "ruleCode", original.ruleCode(), "ruleVersion", original.ruleVersion(),
                        "rootId", original.id(), "reversalOfId", original.id(), "evidence", json(evidence)));
        requireSingle(rows, "point correction insert");
        return id;
    }

    public boolean reversalAbsent(UUID tenantId, UUID originalId) {
        Boolean exists = jdbc.queryForObject("""
                select exists(select 1 from reward.point_transaction
                 where tenant_id=:tenantId and reversal_of_id=:originalId and change_action='REVERSAL'
                   and employee_event_type='P015_POINTS' and not is_deleted)
                """, params("tenantId", tenantId, "originalId", originalId), Boolean.class);
        return !Boolean.TRUE.equals(exists);
    }

    public long balance(UUID tenantId, UUID employeeId, String pointType) {
        Long value = jdbc.queryForObject("""
                select coalesce(sum(points_delta),0) from reward.point_transaction
                 where tenant_id=:tenantId and owner_employee_id=:employeeId and point_type=:pointType
                   and employee_event_type='P015_POINTS' and status='POSTED' and not is_deleted
                """, params("tenantId", tenantId, "employeeId", employeeId, "pointType", pointType), Long.class);
        return value == null ? 0L : value;
    }

    public void insertBalanceSnapshot(
            UUID tenantId, UUID employeeId, String pointType, long balance,
            UUID workflowInstanceId, UUID actorId) {
        jdbc.update("""
                insert into reward.point_balance_snapshot(
                  id,tenant_id,employee_id,point_type,balance_points,workflow_instance_id,calculated_at,created_by)
                values(gen_random_uuid(),:tenantId,:employeeId,:pointType,:balance,:workflowInstanceId,now(),:actorId)
                """, params("tenantId", tenantId, "employeeId", employeeId, "pointType", pointType,
                        "balance", balance, "workflowInstanceId", workflowInstanceId, "actorId", actorId));
    }

    public Optional<PointLedgerView> find(UUID tenantId, UUID caseId) {
        return jdbc.query(viewSql("and wi.business_object_id=:caseId"),
                params("tenantId", tenantId, "caseId", caseId), this::mapView).stream().findFirst();
    }

    public List<PointLedgerView> list(UUID tenantId) {
        return jdbc.query(viewSql("order by wi.started_at desc,wi.id desc"), params("tenantId", tenantId), this::mapView);
    }

    private String viewSql(String suffix) {
        return """
                select wi.business_object_id id,wi.tenant_id,wi.business_object_no business_no,
                       wi.id workflow_instance_id,wi.instance_no workflow_instance_no,wi.current_node_code,
                       wi.priority,wi.title,wi.context_snapshot,
                       coalesce((select count(*) from workflow.wf_action_log al
                         where al.tenant_id=wi.tenant_id and al.instance_id=wi.id
                           and al.action_code<>'START' and not al.is_deleted),0) version_no,
                       pt.owner_center_id,pt.owner_employee_id,pt.business_date,pt.fact_occurred_at,
                       pt.points_delta,pt.point_type,pt.change_action,pt.reversal_of_id,
                       (select bs.balance_points from reward.point_balance_snapshot bs
                         where bs.tenant_id=wi.tenant_id
                           and bs.employee_id=coalesce(pt.owner_employee_id,nullif(wi.context_snapshot->>'ownerEmployeeId','')::uuid)
                           and bs.point_type=coalesce(pt.point_type,wi.context_snapshot->>'pointType')
                         order by bs.calculated_at desc,bs.id desc limit 1) current_balance
                  from workflow.wf_instance wi
                  left join reward.point_transaction pt
                    on pt.tenant_id=wi.tenant_id and pt.id=wi.business_object_id
                   and pt.employee_event_type='P015_POINTS' and pt.change_action='POST' and not pt.is_deleted
                 where wi.tenant_id=:tenantId and wi.process_code='P015'
                   and wi.business_object_type='reward.point_transaction' and not wi.is_deleted
                """ + suffix;
    }

    private PointLedgerView mapView(ResultSet rs, int rowNum) throws SQLException {
        JsonNode context = parse(rs.getString("context_snapshot"));
        String node = rs.getString("current_node_code");
        UUID center = rs.getObject("owner_center_id", UUID.class);
        UUID employee = rs.getObject("owner_employee_id", UUID.class);
        LocalDate businessDate = rs.getObject("business_date", LocalDate.class);
        Instant occurred = instant(rs, "fact_occurred_at");
        if (center == null) center = uuid(context, "ownerCenterId");
        if (employee == null) employee = uuid(context, "ownerEmployeeId");
        if (businessDate == null) businessDate = date(context, "businessDate");
        if (occurred == null) occurred = instant(context, "factOccurredAt");
        Long points = nullableLong(rs, "points_delta");
        if (points == null && context.has("calculatedPoints")) points = context.path("calculatedPoints").asLong();
        String pointType = rs.getString("point_type");
        if (pointType == null) pointType = text(context, "pointType");
        return new PointLedgerView(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("business_no"),
                rs.getObject("workflow_instance_id", UUID.class), rs.getString("workflow_instance_no"), node,
                Phase11Process.P015.labelFor(node), rs.getInt("version_no"), rs.getString("title"),
                rs.getString("priority"), context.path("riskClass").asText(context.path("riskLevel").asText("NORMAL")),
                center, employee, businessDate, occurred, points, pointType, rs.getString("change_action"),
                rs.getObject("reversal_of_id", UUID.class), nullableLong(rs, "current_balance"), context);
    }

    private PointTransaction mapTransaction(ResultSet rs, int rowNum) throws SQLException {
        return new PointTransaction(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("business_no"),
                rs.getObject("owner_center_id", UUID.class), rs.getObject("owner_employee_id", UUID.class),
                rs.getObject("business_date", LocalDate.class), instant(rs, "fact_occurred_at"), rs.getString("fact_summary"),
                rs.getString("impact_level"), rs.getLong("points_delta"), rs.getString("source_fact_key"),
                rs.getString("source_type"), rs.getString("point_type"), rs.getString("rule_code"), rs.getString("rule_version"));
    }

    private JsonNode parse(String value) throws SQLException {
        try { return value == null ? mapper.createObjectNode() : mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new SQLException("P015 workflow context JSON cannot be parsed", exception); }
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value == null ? mapper.createObjectNode() : value); }
        catch (JsonProcessingException exception) { throw rejected("JSON cannot be serialized"); }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }
    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException exception) { throw rejected("invalid UUID context fact: " + field); }
    }
    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) return null;
        try { return Instant.parse(value); } catch (Exception exception) { throw rejected("invalid timestamp context fact: " + field); }
    }
    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) return null;
        try { return LocalDate.parse(value); } catch (Exception exception) { throw rejected("invalid date context fact: " + field); }
    }
    private static long longValue(JsonNode node, String field) {
        if (!node.has(field) || !node.path(field).canConvertToLong()) throw rejected("required numeric context fact is missing: " + field);
        return node.path(field).asLong();
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return null;
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        for (int i = 0; i < values.length; i += 2) p.addValue((String) values[i], values[i + 1]);
        return p;
    }
    private static void requireSingle(int rows, String operation) {
        if (rows != 1) throw rejected(operation + " expected one row but affected " + rows);
    }
    private static ProcessRejectedException rejected(String message) { return new ProcessRejectedException("P015 " + message); }

    public record PointRule(UUID id, String ruleCode, String versionCode, String sourceType, String pointType,
            long pointsDelta, Long reviewThresholdAbs, Instant effectiveFrom, Instant effectiveTo) {}
    public record PointTransaction(UUID id, UUID tenantId, String businessNo, UUID ownerCenterId,
            UUID ownerEmployeeId, LocalDate businessDate, Instant factOccurredAt, String factSummary,
            String impactLevel, long pointsDelta, String sourceFactKey, String sourceType, String pointType,
            String ruleCode, String ruleVersion) {}
}
