package cn.shangjingu.platform.integration;

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
public class JdbcDataImportRepository implements DataImportService.Repository {
    private final JdbcTemplate jdbc;
    private final String safeStatus;

    public JdbcDataImportRepository(
            JdbcTemplate jdbc,
            @Value("${platform.files.safe-virus-scan-status:CLEAN}") String safeStatus) {
        this.jdbc = jdbc;
        this.safeStatus = safeStatus;
    }

    @Override
    public void insert(DataImportService.DataImportJob j, UUID actorId) {
        jdbc.update("""
                insert into integration.data_import_job(
                    id,tenant_id,business_no,status,version_no,created_by,updated_by,import_type,source_file_id,
                    template_version,total_rows,success_rows,failed_rows,actual_start_at,business_date,environment,
                    result_summary,rollback_plan,system_service_name,tech_impact_scope,tech_risk_level)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                j.id(), j.tenantId(), j.businessNo(), j.status(), j.versionNo(), actorId, actorId, j.importType(),
                j.sourceFileId(), j.templateVersion(), j.totalRows(), j.successRows(), j.failedRows(), j.actualStartAt(),
                j.businessDate(), j.environment(), j.resultSummary(), j.rollbackPlan(), j.systemServiceName(),
                j.techImpactScope(), j.techRiskLevel());
    }

    @Override
    public Optional<DataImportService.DataImportJob> find(UUID tenantId, UUID id) {
        return jdbc.query("""
                select id,tenant_id,business_no,status,version_no,import_type,source_file_id,template_version,
                       total_rows,success_rows,failed_rows,validation_result->>'code' validation_code,result_file_id,
                       actual_start_at,actual_end_at,business_date,environment,result_summary,rollback_plan,
                       system_service_name,tech_impact_scope,tech_risk_level,created_by
                from integration.data_import_job
                where tenant_id=? and id=? and not is_deleted
                """, (rs, n) -> map(rs), tenantId, id).stream().findFirst();
    }

    @Override
    public List<DataImportService.ImportItem> items(UUID tenantId, UUID id) {
        return jdbc.query("""
                select item_seq,field_code,item_key,item_name,item_value_text
                from integration.data_import_job_item
                where tenant_id=? and master_id=? and not is_deleted
                order by item_seq,id
                """, (rs, n) -> new DataImportService.ImportItem(
                        rs.getInt("item_seq"), rs.getString("field_code"), rs.getString("item_key"),
                        rs.getString("item_name"), rs.getString("item_value_text")), tenantId, id);
    }

    @Override
    public void assertSafeFile(UUID tenantId, UUID fileId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from document.file_object
                where tenant_id=? and id=? and not is_deleted and virus_scan_status=?
                """, Integer.class, tenantId, fileId, safeStatus);
        if (count == null || count != 1) {
            throw new ProcessRejectedException("data import file is missing or not virus-scan safe");
        }
    }

    @Override
    public int updateStatus(UUID tenantId, UUID id, int expectedVersion, String status, Instant actualEndAt, UUID actorId) {
        return jdbc.update("""
                update integration.data_import_job
                set status=?,version_no=version_no+1,actual_end_at=coalesce(?,actual_end_at),updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and not is_deleted
                """, status, actualEndAt, actorId, tenantId, id, expectedVersion);
    }

    @Override
    public int savePreview(
            UUID tenantId,
            UUID id,
            int expectedVersion,
            DataImportService.ValidationPreview preview,
            UUID actorId) {
        jdbc.update("""
                update integration.data_import_job_item
                set is_deleted=true,deleted_at=now(),updated_by=?,updated_at=now()
                where tenant_id=? and master_id=? and not is_deleted
                """, actorId, tenantId, id);
        for (DataImportService.ImportItem item : preview.items()) {
            jdbc.update("""
                    insert into integration.data_import_job_item(
                        id,tenant_id,created_by,updated_by,master_id,field_code,item_seq,item_key,item_name,item_value_text,sort_no)
                    values (?,?,?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), tenantId, actorId, actorId, id, item.fieldCode(), item.itemSeq(),
                    item.itemKey(), item.itemName(), item.valueText(), item.itemSeq());
        }
        return jdbc.update("""
                update integration.data_import_job
                set status='S06',version_no=version_no+1,total_rows=?,
                    validation_result=jsonb_build_object('code',cast(? as text),'summary',cast(? as text)),
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and status='S05' and not is_deleted
                """, preview.totalRows(), preview.code(), preview.summary(), actorId, tenantId, id, expectedVersion);
    }

    @Override
    public void enqueueExecution(DataImportService.DataImportJob job, int targetVersion, UUID actorId) {
        String eventKey = "P018-" + job.id() + "-EXECUTE-V" + targetVersion;
        jdbc.update("""
                insert into core.outbox_event(
                    id,tenant_id,created_by,updated_by,aggregate_type,aggregate_id,event_type,event_version,payload,event_key)
                values (?,?,?,?,?,?,?,?,jsonb_build_object(
                    'tenant_id',cast(? as text),'job_id',cast(? as text),'expected_version',cast(? as integer)),?)
                on conflict (tenant_id,event_key) do nothing
                """, UUID.randomUUID(), job.tenantId(), actorId, actorId, "integration.data_import_job", job.id(),
                "P018_EXECUTE", 1, job.tenantId().toString(), job.id().toString(), targetVersion, eventKey);
    }

    @Override
    public int recordExecutionResult(
            UUID tenantId,
            UUID id,
            int expectedVersion,
            DataImportService.ExecutionResult result,
            UUID actorId) {
        return jdbc.update("""
                update integration.data_import_job
                set status='S09',version_no=version_no+1,total_rows=?,success_rows=?,failed_rows=?,result_file_id=?,
                    result_summary=?,actual_end_at=now(),updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and status='S08' and not is_deleted
                """, result.totalRows(), result.successRows(), result.failedRows(), result.resultFileId(),
                result.resultSummary(), actorId, tenantId, id, expectedVersion);
    }

    private static DataImportService.DataImportJob map(ResultSet rs) throws SQLException {
        return new DataImportService.DataImportJob(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("business_no"),
                rs.getString("status"), rs.getInt("version_no"), rs.getString("import_type"),
                rs.getObject("source_file_id", UUID.class), rs.getString("template_version"), rs.getInt("total_rows"),
                rs.getInt("success_rows"), rs.getInt("failed_rows"), rs.getString("validation_code"),
                rs.getObject("result_file_id", UUID.class), instant(rs, "actual_start_at"), instant(rs, "actual_end_at"),
                rs.getObject("business_date", java.time.LocalDate.class), rs.getString("environment"),
                rs.getString("result_summary"), rs.getString("rollback_plan"), rs.getString("system_service_name"),
                rs.getString("tech_impact_scope"), rs.getString("tech_risk_level"), rs.getObject("created_by", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
