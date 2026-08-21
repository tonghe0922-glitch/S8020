package cn.shangjingu.platform.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDataQualityRepairRepository implements DataQualityRepairService.Repository {
    private final JdbcTemplate jdbc;

    public JdbcDataQualityRepairRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(DataQualityRepairService.QualityIssue q, List<DataQualityRepairService.QualityItem> items, UUID actorId) {
        jdbc.update("""
                insert into audit.data_quality_issue(
                    id,tenant_id,business_no,status,version_no,created_by,updated_by,rule_code,object_type,object_id,
                    issue_type,severity,before_snapshot,actual_start_at,business_date,employee_event_type,environment,
                    result_summary,system_service_name,tech_impact_scope,tech_risk_level)
                values (?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,?,?)
                """, q.id(), q.tenantId(), q.businessNo(), q.status(), q.versionNo(), actorId, actorId, q.ruleCode(),
                q.objectType(), q.objectId(), q.issueType(), q.severity(), q.beforeSnapshotJson(), q.actualStartAt(),
                q.businessDate(), q.employeeEventType(), q.environment(), q.resultSummary(), q.systemServiceName(),
                q.techImpactScope(), q.techRiskLevel());
        for (DataQualityRepairService.QualityItem item : items) insertItem(q.tenantId(), q.id(), item, actorId);
    }

    @Override
    public Optional<DataQualityRepairService.QualityIssue> find(UUID tenantId, UUID id) {
        return jdbc.query("""
                select id,tenant_id,business_no,status,version_no,rule_code,object_type,object_id,issue_type,severity,
                       before_snapshot::text before_snapshot_json,after_snapshot::text after_snapshot_json,root_cause,
                       resolution_action,verified_at,actual_start_at,actual_end_at,business_date,employee_event_type,
                       environment,result_summary,system_service_name,tech_impact_scope,tech_risk_level,created_by
                from audit.data_quality_issue where tenant_id=? and id=? and not is_deleted
                """, (rs, n) -> map(rs), tenantId, id).stream().findFirst();
    }

    @Override
    public List<DataQualityRepairService.QualityItem> items(UUID tenantId, UUID id) {
        return jdbc.query("""
                select item_seq,field_code,item_key,item_name,item_value_text
                from audit.data_quality_issue_item
                where tenant_id=? and master_id=? and not is_deleted order by item_seq,id
                """, (rs, n) -> new DataQualityRepairService.QualityItem(rs.getInt("item_seq"), rs.getString("field_code"),
                        rs.getString("item_key"), rs.getString("item_name"), rs.getString("item_value_text")), tenantId, id);
    }

    @Override
    public int updateStatus(UUID tenantId, UUID id, int expectedVersion, String status, Instant actualEndAt, UUID actorId) {
        return jdbc.update("""
                update audit.data_quality_issue
                set status=?,version_no=version_no+1,actual_end_at=coalesce(?,actual_end_at),updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and not is_deleted
                """, status, actualEndAt, actorId, tenantId, id, expectedVersion);
    }

    @Override
    public int approvePlan(UUID tenantId, UUID id, int expectedVersion, String planJson, UUID reviewerUserId) {
        insertItem(tenantId, id, new DataQualityRepairService.QualityItem(9001, "repair_plan", "approved", "修复方案", planJson), reviewerUserId);
        insertItem(tenantId, id, new DataQualityRepairService.QualityItem(
                9002, "repair_reviewer_user_id", "reviewer", "复核人", reviewerUserId.toString()), reviewerUserId);
        return jdbc.update("""
                update audit.data_quality_issue
                set status='S05',version_no=version_no+1,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and status='S04' and not is_deleted
                """, reviewerUserId, tenantId, id, expectedVersion);
    }

    @Override
    public DataQualityRepairService.RepairControl repairControl(UUID tenantId, UUID id) {
        return jdbc.query("""
                select
                    max(item_value_text) filter (where field_code='repair_reviewer_user_id') reviewer_id,
                    max(item_value_text) filter (where field_code='repair_plan') plan_json
                from audit.data_quality_issue_item
                where tenant_id=? and master_id=? and not is_deleted
                """, rs -> {
                    if (!rs.next() || rs.getString("reviewer_id") == null || rs.getString("plan_json") == null) return null;
                    return new DataQualityRepairService.RepairControl(UUID.fromString(rs.getString("reviewer_id")), rs.getString("plan_json"));
                }, tenantId, id);
    }

    @Override
    public int recordRepair(UUID tenantId, UUID id, int expectedVersion, String afterSnapshotJson,
            String resolutionAction, UUID executorUserId) {
        insertItem(tenantId, id, new DataQualityRepairService.QualityItem(
                9003, "repair_executor_user_id", "executor", "执行人", executorUserId.toString()), executorUserId);
        return jdbc.update("""
                update audit.data_quality_issue
                set status='S06',version_no=version_no+1,after_snapshot=cast(? as jsonb),resolution_action=?,
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and status='S05' and not is_deleted
                """, afterSnapshotJson, resolutionAction, executorUserId, tenantId, id, expectedVersion);
    }

    @Override
    public int recordVerified(UUID tenantId, UUID id, int expectedVersion, UUID actorId) {
        return jdbc.update("""
                update audit.data_quality_issue
                set status='S08',version_no=version_no+1,verified_at=now(),updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and status='S07' and not is_deleted
                """, actorId, tenantId, id, expectedVersion);
    }

    private void insertItem(UUID tenantId, UUID masterId, DataQualityRepairService.QualityItem item, UUID actorId) {
        jdbc.update("""
                insert into audit.data_quality_issue_item(
                    id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_key,item_name,item_value_text,sort_no)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, actorId, actorId, masterId, item.fieldCode(), item.itemSeq(), item.itemKey(),
                item.itemName(), item.valueText(), item.itemSeq());
    }

    private static DataQualityRepairService.QualityIssue map(ResultSet rs) throws SQLException {
        return new DataQualityRepairService.QualityIssue(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("business_no"),
                rs.getString("status"), rs.getInt("version_no"), rs.getString("rule_code"), rs.getString("object_type"),
                rs.getObject("object_id", UUID.class), rs.getString("issue_type"), rs.getString("severity"),
                rs.getString("before_snapshot_json"), rs.getString("after_snapshot_json"), rs.getString("root_cause"),
                rs.getString("resolution_action"), instant(rs, "verified_at"), instant(rs, "actual_start_at"),
                instant(rs, "actual_end_at"), rs.getObject("business_date", java.time.LocalDate.class),
                rs.getString("employee_event_type"), rs.getString("environment"), rs.getString("result_summary"),
                rs.getString("system_service_name"), rs.getString("tech_impact_scope"), rs.getString("tech_risk_level"),
                rs.getObject("created_by", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
