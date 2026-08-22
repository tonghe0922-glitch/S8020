package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkflowOrchestrationRepository implements WorkflowOrchestrationService.Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcWorkflowOrchestrationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void insert(WorkflowOrchestrationService.Orchestration value, UUID actorId) {
        jdbc.update(
                """
                insert into workflow.wf_orchestration_instance(
                    id,tenant_id,business_no,workflow_instance_id,status,version_no,process_code,master_order_no,
                    lead_center_id,participating_centers,current_milestone,critical_path,raci_matrix,master_change_version,
                    completion_rate,actual_amount,actual_end_at,actual_start_at,business_date,contact_name,
                    content_asset_no,content_title,content_type,customer_id,customer_name,guest_team_name,
                    incident_area_id,incident_patrol_no,incident_type,item_asset_id,item_asset_name,person_name,person_no,
                    program_version_id,reception_level,reception_team_no,result_summary,show_session_no,show_time,
                    spec_model,target_job_id,created_by,updated_by)
                values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                value.id(),
                value.tenantId(),
                value.businessNo(),
                value.workflowInstanceId(),
                value.status(),
                value.versionNo(),
                value.processCode(),
                value.masterOrderNo(),
                value.leadCenterId(),
                json(value.participatingCenters()),
                value.currentMilestone(),
                json(value.criticalPath()),
                json(value.raciMatrix()),
                value.masterChangeVersion(),
                value.completionRate(),
                value.actualAmount(),
                timestamp(value.actualEndAt()),
                timestamp(value.actualStartAt()),
                value.businessDate(),
                value.contactName(),
                value.contentAssetNo(),
                value.contentTitle(),
                value.contentType(),
                value.customerId(),
                value.customerName(),
                value.guestTeamName(),
                value.incidentAreaId(),
                value.incidentPatrolNo(),
                value.incidentType(),
                value.itemAssetId(),
                value.itemAssetName(),
                value.personName(),
                value.personNo(),
                value.programVersionId(),
                value.receptionLevel(),
                value.receptionTeamNo(),
                value.resultSummary(),
                value.showSessionNo(),
                timestamp(value.showTime()),
                value.specModel(),
                value.targetJobId(),
                actorId,
                actorId);
    }

    @Override
    public Optional<WorkflowOrchestrationService.Orchestration> find(UUID tenantId, UUID orchestrationId) {
        return jdbc
                .query(
                        orchestrationSql("where tenant_id=? and id=? and not is_deleted"),
                        (rs, row) -> orchestration(rs),
                        tenantId,
                        orchestrationId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<WorkflowOrchestrationService.Orchestration> lock(UUID tenantId, UUID orchestrationId) {
        return jdbc
                .query(
                        orchestrationSql("where tenant_id=? and id=? and not is_deleted for update"),
                        (rs, row) -> orchestration(rs),
                        tenantId,
                        orchestrationId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<WorkflowOrchestrationService.WorkflowReference> findWorkflowInstance(
            UUID tenantId, UUID workflowInstanceId) {
        return jdbc
                .query(
                        """
                select id,process_code,status
                from workflow.wf_instance
                where tenant_id=? and id=? and not is_deleted
                """,
                        (rs, row) -> new WorkflowOrchestrationService.WorkflowReference(
                                rs.getObject("id", UUID.class), rs.getString("process_code"), rs.getString("status")),
                        tenantId,
                        workflowInstanceId)
                .stream()
                .findFirst();
    }

    @Override
    public int bumpVersion(UUID tenantId, UUID orchestrationId, int expectedVersion, UUID actorId) {
        return jdbc.update(
                """
                update workflow.wf_orchestration_instance
                set version_no=version_no+1,master_change_version=master_change_version+1,
                    updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and not is_deleted
                """,
                actorId,
                tenantId,
                orchestrationId,
                expectedVersion);
    }

    @Override
    public int updateProgress(
            UUID tenantId,
            UUID orchestrationId,
            int expectedVersion,
            String milestone,
            java.math.BigDecimal completionRate,
            UUID actorId) {
        return jdbc.update(
                """
                update workflow.wf_orchestration_instance
                set current_milestone=?,completion_rate=?,version_no=version_no+1,
                    master_change_version=master_change_version+1,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and not is_deleted
                """,
                milestone,
                completionRate,
                actorId,
                tenantId,
                orchestrationId,
                expectedVersion);
    }

    @Override
    public int updateStatus(
            UUID tenantId,
            UUID orchestrationId,
            int expectedVersion,
            String status,
            Instant actualEndAt,
            UUID actorId) {
        return jdbc.update(
                """
                update workflow.wf_orchestration_instance
                set status=?,actual_end_at=coalesce(?,actual_end_at),version_no=version_no+1,
                    master_change_version=master_change_version+1,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and version_no=? and not is_deleted
                """,
                status,
                timestamp(actualEndAt),
                actorId,
                tenantId,
                orchestrationId,
                expectedVersion);
    }

    @Override
    public boolean itemExists(UUID tenantId, UUID orchestrationId, String fieldCode, int itemSeq, String itemKey) {
        Integer count = jdbc.queryForObject(
                """
                select count(*)
                from workflow.wf_orchestration_instance_item
                where tenant_id=? and master_id=? and field_code=? and item_seq=?
                  and item_key is not distinct from ? and not is_deleted
                """,
                Integer.class,
                tenantId,
                orchestrationId,
                fieldCode,
                itemSeq,
                itemKey);
        return count != null && count > 0;
    }

    @Override
    public void insertItem(WorkflowOrchestrationService.OrchestrationItem item, UUID actorId) {
        jdbc.update(
                """
                insert into workflow.wf_orchestration_instance_item(
                    id,tenant_id,master_id,field_code,item_seq,item_key,item_name,item_value_text,item_value_number,
                    item_value_json,related_object_type,related_object_id,amount,quantity,sort_no,created_by,updated_by)
                values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)
                """,
                item.id(),
                item.tenantId(),
                item.masterId(),
                item.fieldCode(),
                item.itemSeq(),
                item.itemKey(),
                item.itemName(),
                item.itemValueText(),
                item.itemValueNumber(),
                json(item.itemValueJson()),
                item.relatedObjectType(),
                item.relatedObjectId(),
                item.amount(),
                item.quantity(),
                item.sortNo(),
                actorId,
                actorId);
    }

    @Override
    public List<WorkflowOrchestrationService.OrchestrationItem> listItems(UUID tenantId, UUID orchestrationId) {
        return jdbc.query(
                """
                select id,tenant_id,master_id,field_code,item_seq,item_key,item_name,item_value_text,item_value_number,
                       item_value_json,related_object_type,related_object_id,amount,quantity,sort_no
                from workflow.wf_orchestration_instance_item
                where tenant_id=? and master_id=? and not is_deleted
                order by sort_no,item_seq,id
                """,
                (rs, row) -> item(rs),
                tenantId,
                orchestrationId);
    }

    @Override
    public boolean linkExists(UUID tenantId, UUID orchestrationId, UUID childInstanceId) {
        Integer count = jdbc.queryForObject(
                """
                select count(*)
                from workflow.wf_orchestration_link
                where tenant_id=? and orchestration_id=? and child_instance_id=? and not is_deleted
                """,
                Integer.class,
                tenantId,
                orchestrationId,
                childInstanceId);
        return count != null && count > 0;
    }

    @Override
    public void insertLink(WorkflowOrchestrationService.OrchestrationLink link, UUID actorId) {
        jdbc.update(
                """
                insert into workflow.wf_orchestration_link(
                    id,tenant_id,orchestration_id,child_process_code,child_instance_id,dependency_type,
                    milestone_code,status,required,created_by,updated_by)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """,
                link.id(),
                link.tenantId(),
                link.orchestrationId(),
                link.childProcessCode(),
                link.childInstanceId(),
                link.dependencyType(),
                link.milestoneCode(),
                link.status(),
                link.required(),
                actorId,
                actorId);
    }

    @Override
    public List<WorkflowOrchestrationService.OrchestrationLink> listLinks(UUID tenantId, UUID orchestrationId) {
        return jdbc.query(
                """
                select id,tenant_id,orchestration_id,child_process_code,child_instance_id,dependency_type,
                       milestone_code,status,required
                from workflow.wf_orchestration_link
                where tenant_id=? and orchestration_id=? and not is_deleted
                order by created_at,id
                """,
                (rs, row) -> link(rs),
                tenantId,
                orchestrationId);
    }

    private WorkflowOrchestrationService.Orchestration orchestration(ResultSet rs) throws SQLException {
        return new WorkflowOrchestrationService.Orchestration(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("business_no"),
                rs.getObject("workflow_instance_id", UUID.class),
                rs.getString("status"),
                rs.getInt("version_no"),
                rs.getString("process_code"),
                rs.getString("master_order_no"),
                rs.getObject("lead_center_id", UUID.class),
                parse(rs.getString("participating_centers")),
                rs.getString("current_milestone"),
                parse(rs.getString("critical_path")),
                parse(rs.getString("raci_matrix")),
                rs.getInt("master_change_version"),
                rs.getBigDecimal("completion_rate"),
                rs.getBigDecimal("actual_amount"),
                instant(rs, "actual_end_at"),
                instant(rs, "actual_start_at"),
                rs.getObject("business_date", java.time.LocalDate.class),
                rs.getString("contact_name"),
                rs.getString("content_asset_no"),
                rs.getString("content_title"),
                rs.getString("content_type"),
                rs.getString("customer_id"),
                rs.getString("customer_name"),
                rs.getString("guest_team_name"),
                rs.getString("incident_area_id"),
                rs.getString("incident_patrol_no"),
                rs.getString("incident_type"),
                rs.getString("item_asset_id"),
                rs.getString("item_asset_name"),
                rs.getString("person_name"),
                rs.getString("person_no"),
                rs.getString("program_version_id"),
                rs.getString("reception_level"),
                rs.getString("reception_team_no"),
                rs.getString("result_summary"),
                rs.getString("show_session_no"),
                instant(rs, "show_time"),
                rs.getString("spec_model"),
                rs.getString("target_job_id"));
    }

    private WorkflowOrchestrationService.OrchestrationItem item(ResultSet rs) throws SQLException {
        return new WorkflowOrchestrationService.OrchestrationItem(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("master_id", UUID.class),
                rs.getString("field_code"),
                rs.getInt("item_seq"),
                rs.getString("item_key"),
                rs.getString("item_name"),
                rs.getString("item_value_text"),
                rs.getBigDecimal("item_value_number"),
                parse(rs.getString("item_value_json")),
                rs.getString("related_object_type"),
                rs.getObject("related_object_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("quantity"),
                rs.getInt("sort_no"));
    }

    private WorkflowOrchestrationService.OrchestrationLink link(ResultSet rs) throws SQLException {
        return new WorkflowOrchestrationService.OrchestrationLink(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("orchestration_id", UUID.class),
                rs.getString("child_process_code"),
                rs.getObject("child_instance_id", UUID.class),
                rs.getString("dependency_type"),
                rs.getString("milestone_code"),
                rs.getString("status"),
                rs.getBoolean("required"));
    }

    private String orchestrationSql(String suffix) {
        return """
                select id,tenant_id,business_no,workflow_instance_id,status,version_no,process_code,master_order_no,
                       lead_center_id,participating_centers,current_milestone,critical_path,raci_matrix,master_change_version,
                       completion_rate,actual_amount,actual_end_at,actual_start_at,business_date,contact_name,
                       content_asset_no,content_title,content_type,customer_id,customer_name,guest_team_name,
                       incident_area_id,incident_patrol_no,incident_type,item_asset_id,item_asset_name,person_name,person_no,
                       program_version_id,reception_level,reception_team_no,result_summary,show_session_no,show_time,
                       spec_model,target_job_id
                from workflow.wf_orchestration_instance
                """
                + suffix;
    }

    private String json(JsonNode node) {
        if (node == null) return null;
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw WorkflowException.invalid("orchestration JSON cannot be serialized");
        }
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new WorkflowException(
                    WorkflowException.Code.INVALID_DEFINITION, "invalid persisted orchestration JSON", ex);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
