package cn.shangjingu.platform.welfare;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCareCaseRepository implements CareCaseService.Repository {
    private final JdbcTemplate jdbc;

    public JdbcCareCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(CareCaseService.CareCase c, UUID actorId) {
        jdbc.update("""
                insert into welfare.care_case(
                    id,tenant_id,business_no,status,version_no,created_by,updated_by,
                    source_channel,business_date,subject,reason,priority,risk_level,
                    owner_center_id,owner_department_id,owner_employee_id,benefit_amount,budget_item_id,
                    cost_center_id,currency,employee_event_type,fact_occurred_at,fact_summary,
                    impact_effective_date,impact_level,points_delta)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                c.id(), c.tenantId(), c.businessNo(), c.status(), c.versionNo(), actorId, actorId,
                c.sourceChannel(), c.businessDate(), c.subject(), c.reason(), valueOr(c.priority(), "NORMAL"), c.riskLevel(),
                c.ownerCenterId(), c.ownerDepartmentId(), c.ownerEmployeeId(), c.benefitAmount(), c.budgetItemId(),
                c.costCenterId(), c.currency(), c.employeeEventType(), c.factOccurredAt(), c.factSummary(),
                c.impactEffectiveDate(), c.impactLevel(), c.pointsDelta());
    }

    @Override
    public Optional<CareCaseService.CareCase> find(UUID tenantId, UUID id) {
        return jdbc.query("""
                select id,tenant_id,business_no,status,version_no,source_channel,business_date,subject,reason,
                       priority,risk_level,owner_center_id,owner_department_id,owner_employee_id,benefit_amount,
                       budget_item_id,cost_center_id,currency,employee_event_type,fact_occurred_at,fact_summary,
                       impact_effective_date,impact_level,points_delta,result_summary,actual_start_at,actual_end_at,closed_at
                from welfare.care_case
                where tenant_id=? and id=? and not is_deleted
                """, (rs, n) -> map(rs), tenantId, id).stream().findFirst();
    }

    @Override
    public int updateStatus(
            UUID tenantId,
            UUID id,
            int expectedVersion,
            String status,
            String resultSummary,
            Instant actualStartAt,
            Instant actualEndAt,
            Instant closedAt,
            UUID actorId) {
        return jdbc.update("""
                update welfare.care_case
                set status=?,version_no=version_no+1,result_summary=coalesce(?,result_summary),
                    actual_start_at=coalesce(?,actual_start_at),actual_end_at=coalesce(?,actual_end_at),
                    closed_at=coalesce(?,closed_at),updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and not is_deleted
                """, status, resultSummary, actualStartAt, actualEndAt, closedAt, actorId, tenantId, id, expectedVersion);
    }

    public Optional<CanonicalCase> findCanonical(UUID tenantId, UUID id) {
        return jdbc.query(canonicalSelect("and c.id=?"), (rs, n) -> mapCanonical(rs), tenantId, id)
                .stream().findFirst();
    }

    public List<CanonicalCase> listCanonical(UUID tenantId) {
        return jdbc.query(canonicalSelect("order by c.created_at desc,c.id desc"), (rs, n) -> mapCanonical(rs), tenantId);
    }

    public int bindWorkflow(
            UUID tenantId,
            UUID id,
            int expectedVersion,
            UUID workflowInstanceId,
            String nodeCode,
            UUID actorId) {
        return jdbc.update("""
                update welfare.care_case
                set workflow_instance_id=?,current_node_code=?,status=?,version_no=version_no+1,
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=?
                  and employee_event_type='P016_CARE' and not is_deleted
                """, workflowInstanceId, nodeCode, nodeCode, actorId, tenantId, id, expectedVersion);
    }

    public int advanceCanonical(
            UUID tenantId,
            UUID id,
            int expectedVersion,
            String expectedNode,
            String targetNode,
            String resultSummary,
            boolean markStarted,
            boolean close,
            UUID actorId) {
        return jdbc.update("""
                update welfare.care_case
                set current_node_code=?,status=?,version_no=version_no+1,
                    result_summary=coalesce(?,result_summary),
                    actual_start_at=case when ? then coalesce(actual_start_at,now()) else actual_start_at end,
                    actual_end_at=case when ? then coalesce(actual_end_at,now()) else actual_end_at end,
                    closed_at=case when ? then coalesce(closed_at,now()) else closed_at end,
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and current_node_code=?
                  and employee_event_type='P016_CARE' and not is_deleted
                """,
                targetNode, close ? "CLOSED" : targetNode, trimToNull(resultSummary),
                markStarted, close, close, actorId,
                tenantId, id, expectedVersion, expectedNode);
    }

    public int insertFact(
            UUID tenantId,
            UUID careCaseId,
            String factType,
            String summary,
            String evidenceReference,
            UUID actorEmployeeId,
            UUID actorId) {
        return jdbc.update("""
                insert into welfare.care_case_fact(
                    id,tenant_id,care_case_id,fact_type,summary,evidence_reference,
                    actor_employee_id,occurred_at,created_by,created_at)
                values (gen_random_uuid(),?,?,?,?,?,?,now(),?,now())
                on conflict (tenant_id,care_case_id,fact_type) do nothing
                """,
                tenantId, careCaseId, factType, summary, trimToNull(evidenceReference), actorEmployeeId, actorId);
    }

    public List<CareFact> facts(UUID tenantId, UUID careCaseId) {
        return jdbc.query("""
                select id,fact_type,summary,evidence_reference,actor_employee_id,occurred_at
                from welfare.care_case_fact
                where tenant_id=? and care_case_id=?
                order by occurred_at,id
                """, (rs, n) -> new CareFact(
                        rs.getObject("id", UUID.class),
                        rs.getString("fact_type"),
                        rs.getString("summary"),
                        rs.getString("evidence_reference"),
                        rs.getObject("actor_employee_id", UUID.class),
                        instant(rs, "occurred_at")), tenantId, careCaseId);
    }

    private static String canonicalSelect(String suffix) {
        return """
                select c.id,c.tenant_id,c.business_no,c.workflow_instance_id,c.current_node_code,c.status,c.version_no,
                       c.source_channel,c.business_date,c.subject,c.reason,c.priority,c.risk_level,
                       c.owner_center_id,c.owner_department_id,c.owner_employee_id,c.benefit_amount,c.budget_item_id,
                       c.cost_center_id,c.currency,c.employee_event_type,c.fact_occurred_at,c.fact_summary,
                       c.impact_effective_date,c.impact_level,c.result_summary,c.actual_start_at,c.actual_end_at,c.closed_at,
                       c.created_at,c.updated_at
                from welfare.care_case c
                where c.tenant_id=? and c.employee_event_type='P016_CARE' and not c.is_deleted
                """ + suffix;
    }

    private static CareCaseService.CareCase map(ResultSet rs) throws SQLException {
        return new CareCaseService.CareCase(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("business_no"),
                rs.getString("status"),
                rs.getInt("version_no"),
                rs.getString("source_channel"),
                rs.getObject("business_date", LocalDate.class),
                rs.getString("subject"),
                rs.getString("reason"),
                rs.getString("priority"),
                rs.getString("risk_level"),
                rs.getObject("owner_center_id", UUID.class),
                rs.getObject("owner_department_id", UUID.class),
                rs.getObject("owner_employee_id", UUID.class),
                rs.getBigDecimal("benefit_amount"),
                rs.getString("budget_item_id"),
                rs.getString("cost_center_id"),
                rs.getString("currency"),
                rs.getString("employee_event_type"),
                instant(rs, "fact_occurred_at"),
                rs.getString("fact_summary"),
                rs.getObject("impact_effective_date", LocalDate.class),
                rs.getString("impact_level"),
                rs.getObject("points_delta", Long.class),
                rs.getString("result_summary"),
                instant(rs, "actual_start_at"),
                instant(rs, "actual_end_at"),
                instant(rs, "closed_at"));
    }

    private static CanonicalCase mapCanonical(ResultSet rs) throws SQLException {
        return new CanonicalCase(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("business_no"),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("current_node_code"),
                rs.getString("status"),
                rs.getInt("version_no"),
                rs.getString("source_channel"),
                rs.getObject("business_date", LocalDate.class),
                rs.getString("subject"),
                rs.getString("reason"),
                rs.getString("priority"),
                rs.getString("risk_level"),
                rs.getObject("owner_center_id", UUID.class),
                rs.getObject("owner_department_id", UUID.class),
                rs.getObject("owner_employee_id", UUID.class),
                rs.getBigDecimal("benefit_amount"),
                rs.getString("budget_item_id"),
                rs.getString("cost_center_id"),
                rs.getString("currency"),
                rs.getString("employee_event_type"),
                instant(rs, "fact_occurred_at"),
                rs.getString("fact_summary"),
                rs.getObject("impact_effective_date", LocalDate.class),
                rs.getString("impact_level"),
                rs.getString("result_summary"),
                instant(rs, "actual_start_at"),
                instant(rs, "actual_end_at"),
                instant(rs, "closed_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CanonicalCase(
            UUID id,
            UUID tenantId,
            String businessNo,
            UUID workflowInstanceId,
            String currentNodeCode,
            String status,
            int versionNo,
            String sourceChannel,
            LocalDate businessDate,
            String subject,
            String reason,
            String priority,
            String riskLevel,
            UUID ownerCenterId,
            UUID ownerDepartmentId,
            UUID ownerEmployeeId,
            BigDecimal benefitAmount,
            String budgetItemId,
            String costCenterId,
            String currency,
            String employeeEventType,
            Instant factOccurredAt,
            String factSummary,
            LocalDate impactEffectiveDate,
            String impactLevel,
            String resultSummary,
            Instant actualStartAt,
            Instant actualEndAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record CareFact(
            UUID id,
            String factType,
            String summary,
            String evidenceReference,
            UUID actorEmployeeId,
            Instant occurredAt) {}
}
