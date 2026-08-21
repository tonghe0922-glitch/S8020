package cn.shangjingu.platform.audit;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSensitiveExportRepository implements SensitiveExportService.Repository {
    private final JdbcTemplate jdbc;
    private final String safeStatus;

    public JdbcSensitiveExportRepository(JdbcTemplate jdbc,
            @Value("${platform.files.safe-virus-scan-status:CLEAN}") String safeStatus) {
        this.jdbc = jdbc;
        this.safeStatus = safeStatus;
    }

    @Override
    public void insert(SensitiveExportService.ExportRequest r, List<SensitiveExportService.ExportItem> items, UUID actorId) {
        jdbc.update("""
                insert into audit.data_export_request(
                    id,tenant_id,business_no,status,version_no,created_by,updated_by,export_type,data_scope,field_scope,
                    purpose,approval_level,download_count,actual_start_at,business_date,environment,result_summary,
                    rollback_plan,system_service_name,tech_impact_scope,tech_risk_level)
                values (?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?,?,?,?,?,?,?,?,?)
                """,
                r.id(), r.tenantId(), r.businessNo(), r.status(), r.versionNo(), actorId, actorId, r.exportType(),
                r.dataScopeJson(), r.fieldScopeJson(), r.purpose(), r.approvalLevel(), r.downloadCount(),
                r.actualStartAt(), r.businessDate(), r.environment(), r.resultSummary(), r.rollbackPlan(),
                r.systemServiceName(), r.techImpactScope(), r.techRiskLevel());
        for (SensitiveExportService.ExportItem item : items) {
            jdbc.update("""
                    insert into audit.data_export_request_item(
                        id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_key,item_name,sort_no)
                    values (?,?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), r.tenantId(), actorId, actorId, r.id(), item.fieldCode(), item.itemSeq(),
                    item.itemKey(), item.itemName(), item.itemSeq());
        }
    }

    @Override
    public Optional<SensitiveExportService.ExportRequest> find(UUID tenantId, UUID id) {
        return jdbc.query("""
                select id,tenant_id,business_no,status,version_no,export_type,data_scope::text data_scope_json,
                       field_scope::text field_scope_json,purpose,approval_level,watermark_text,expire_at,file_id,
                       download_count,actual_start_at,actual_end_at,business_date,environment,result_summary,
                       rollback_plan,system_service_name,tech_impact_scope,tech_risk_level,created_by
                from audit.data_export_request
                where tenant_id=? and id=? and not is_deleted
                """, (rs, n) -> map(rs), tenantId, id).stream().findFirst();
    }

    @Override
    public List<SensitiveExportService.ExportItem> items(UUID tenantId, UUID id) {
        return jdbc.query("""
                select item_seq,field_code,item_key,item_name
                from audit.data_export_request_item
                where tenant_id=? and master_id=? and not is_deleted
                order by item_seq,id
                """, (rs, n) -> new SensitiveExportService.ExportItem(
                        rs.getInt("item_seq"), rs.getString("field_code"), rs.getString("item_key"), rs.getString("item_name")),
                tenantId, id);
    }

    @Override
    public void assertSafeFile(UUID tenantId, UUID fileId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from document.file_object
                where tenant_id=? and id=? and not is_deleted and virus_scan_status=?
                """, Integer.class, tenantId, fileId, safeStatus);
        if (count == null || count != 1) throw new ProcessRejectedException("export file is missing or not virus-scan safe");
    }

    @Override
    public int updateStatus(UUID tenantId, UUID id, int expectedVersion, String status, Instant actualEndAt, UUID actorId) {
        return jdbc.update("""
                update audit.data_export_request
                set status=?,version_no=version_no+1,actual_end_at=coalesce(?,actual_end_at),updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and not is_deleted
                """, status, actualEndAt, actorId, tenantId, id, expectedVersion);
    }

    @Override
    public void enqueueGeneration(SensitiveExportService.ExportRequest request, int targetVersion, UUID actorId) {
        String eventKey = "P019-" + request.id() + "-GENERATE-V" + targetVersion;
        jdbc.update("""
                insert into core.outbox_event(
                    id,tenant_id,created_by,updated_by,aggregate_type,aggregate_id,event_type,event_version,payload,event_key)
                values (?,?,?,?,?,?,?,?,jsonb_build_object(
                    'tenant_id',cast(? as text),'request_id',cast(? as text),'expected_version',cast(? as integer)),?)
                on conflict (tenant_id,event_key) do nothing
                """, UUID.randomUUID(), request.tenantId(), actorId, actorId, "audit.data_export_request", request.id(),
                "P019_GENERATE", 1, request.tenantId().toString(), request.id().toString(), targetVersion, eventKey);
    }

    @Override
    public int recordGenerated(UUID tenantId, UUID id, int expectedVersion,
            SensitiveExportService.GenerationResult result, UUID actorId) {
        return jdbc.update("""
                update audit.data_export_request
                set status='S06',version_no=version_no+1,file_id=?,watermark_text=?,expire_at=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and status='S05' and not is_deleted
                """, result.fileId(), result.watermarkText(), result.expireAt(), actorId, tenantId, id, expectedVersion);
    }

    @Override
    public int recordDownloadGrant(UUID tenantId, UUID id, int expectedVersion, UUID actorId) {
        return jdbc.update("""
                update audit.data_export_request
                set status='S08',version_no=version_no+1,download_count=download_count+1,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and status='S07' and not is_deleted
                """, actorId, tenantId, id, expectedVersion);
    }

    private static SensitiveExportService.ExportRequest map(ResultSet rs) throws SQLException {
        return new SensitiveExportService.ExportRequest(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("business_no"),
                rs.getString("status"), rs.getInt("version_no"), rs.getString("export_type"), rs.getString("data_scope_json"),
                rs.getString("field_scope_json"), rs.getString("purpose"), rs.getString("approval_level"),
                rs.getString("watermark_text"), instant(rs, "expire_at"), rs.getObject("file_id", UUID.class),
                rs.getInt("download_count"), instant(rs, "actual_start_at"), instant(rs, "actual_end_at"),
                rs.getObject("business_date", java.time.LocalDate.class), rs.getString("environment"),
                rs.getString("result_summary"), rs.getString("rollback_plan"), rs.getString("system_service_name"),
                rs.getString("tech_impact_scope"), rs.getString("tech_risk_level"), rs.getObject("created_by", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
