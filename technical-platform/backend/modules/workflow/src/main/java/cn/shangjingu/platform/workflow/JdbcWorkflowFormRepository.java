package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkflowFormRepository implements WorkflowFormService.Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowFormRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public int nextVersionNo(UUID tenantId, String formCode, String processCode, String nodeCode) {
        Integer next = jdbc.queryForObject("""
                select coalesce(max(version_no),0)+1
                from workflow.wf_form_definition
                where tenant_id=? and form_code=? and process_code=? and node_code=? and not is_deleted
                """, Integer.class, tenantId, formCode, processCode, nodeCode);
        return next == null ? 1 : next;
    }

    @Override
    public void insertFormDefinition(WorkflowFormService.FormDefinition form, UUID actorId) {
        jdbc.update("""
                insert into workflow.wf_form_definition(
                    id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,layout_schema,
                    validation_schema,visibility_matrix,edit_matrix,enabled,created_by,updated_by)
                values (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?)
                """, form.id(), form.tenantId(), form.formCode(), form.formName(), form.processCode(), form.nodeCode(),
                form.versionNo(), json(form.fieldSchema()), json(form.layoutSchema()), json(form.validationSchema()),
                json(form.visibilityMatrix()), json(form.editMatrix()), form.enabled(), actorId, actorId);
    }

    @Override
    public Optional<WorkflowFormService.FormDefinition> lockFormDefinition(UUID tenantId, UUID formDefinitionId) {
        return jdbc.query(formSelect("where tenant_id=? and id=? and not is_deleted for update"),
                (rs, row) -> mapForm(rs), tenantId, formDefinitionId).stream().findFirst();
    }

    @Override
    public Optional<WorkflowFormService.FormDefinition> findPublishedForm(UUID tenantId, UUID formDefinitionId) {
        return jdbc.query(formSelect("where tenant_id=? and id=? and enabled and not is_deleted"),
                (rs, row) -> mapForm(rs), tenantId, formDefinitionId).stream().findFirst();
    }

    @Override
    public int publishForm(UUID tenantId, UUID formDefinitionId, UUID actorId) {
        return jdbc.update("""
                update workflow.wf_form_definition
                set enabled=true,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and not enabled and not is_deleted
                """, actorId, tenantId, formDefinitionId);
    }

    @Override
    public Optional<WorkflowFormService.InstanceBinding> findInstance(UUID tenantId, UUID instanceId) {
        return jdbc.query("""
                select id,process_code,current_node_code
                from workflow.wf_instance
                where tenant_id=? and id=? and not is_deleted
                """, (rs, row) -> new WorkflowFormService.InstanceBinding(
                rs.getObject("id", UUID.class), rs.getString("process_code"), rs.getString("current_node_code")),
                tenantId, instanceId).stream().findFirst();
    }

    @Override
    public Optional<WorkflowFormService.TaskBinding> findTask(UUID tenantId, UUID taskId) {
        return jdbc.query("""
                select id,instance_id,node_code,assignee_id,status
                from workflow.wf_task
                where tenant_id=? and id=? and not is_deleted
                """, (rs, row) -> new WorkflowFormService.TaskBinding(
                rs.getObject("id", UUID.class), rs.getObject("instance_id", UUID.class), rs.getString("node_code"),
                rs.getObject("assignee_id", UUID.class), rs.getString("status")), tenantId, taskId).stream().findFirst();
    }

    @Override
    public void insertSubmission(WorkflowFormService.Submission submission, UUID actorId) {
        jdbc.update("""
                insert into workflow.wf_submission(
                    id,tenant_id,submission_no,instance_id,task_id,form_definition_id,form_version,submitter_id,
                    submitted_at,content_hash,status,created_by,updated_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, submission.id(), submission.tenantId(), submission.submissionNo(), submission.instanceId(),
                submission.taskId(), submission.formDefinitionId(), submission.formVersion(), submission.submitterId(),
                Timestamp.from(submission.submittedAt()), submission.contentHash(), submission.status(), actorId, actorId);
    }

    @Override
    public void insertValues(UUID tenantId, UUID submissionId, List<WorkflowFormService.FieldValue> values, UUID actorId) {
        for (WorkflowFormService.FieldValue value : values) {
            jdbc.update("""
                    insert into workflow.wf_submission_value(
                        id,tenant_id,submission_id,field_code,value_type,value_text,value_number,value_datetime,
                        value_boolean,value_json,search_hash,sensitive_level,is_encrypted,created_by,updated_by)
                    values (?,?,?,?,?,?,?, ?,?,?::jsonb,?,?,?,?,?)
                    """, UUID.randomUUID(), tenantId, submissionId, value.fieldCode(), value.valueType(), value.valueText(),
                    value.valueNumber(), timestamp(value.valueDatetime()), value.valueBoolean(), json(value.valueJson()),
                    value.searchHash(), value.sensitiveLevel(), value.encrypted(), actorId, actorId);
        }
    }

    @Override
    public Optional<WorkflowFormService.Submission> findSubmission(UUID tenantId, UUID submissionId) {
        return jdbc.query(submissionSelect("where tenant_id=? and id=? and not is_deleted"),
                (rs, row) -> mapSubmission(rs), tenantId, submissionId).stream().findFirst();
    }

    @Override
    public Optional<WorkflowFormService.Submission> lockSubmission(UUID tenantId, UUID submissionId) {
        return jdbc.query(submissionSelect("where tenant_id=? and id=? and not is_deleted for update"),
                (rs, row) -> mapSubmission(rs), tenantId, submissionId).stream().findFirst();
    }

    @Override
    public Set<String> listFieldCodes(UUID tenantId, UUID submissionId) {
        return new LinkedHashSet<>(jdbc.query("""
                select field_code from workflow.wf_submission_value
                where tenant_id=? and submission_id=? and not is_deleted
                order by field_code
                """, (rs, row) -> rs.getString(1), tenantId, submissionId));
    }

    @Override
    public int updateSubmissionStatus(UUID tenantId, UUID submissionId, String expectedStatus, String status, UUID actorId) {
        return jdbc.update("""
                update workflow.wf_submission
                set status=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and status=? and not is_deleted
                """, status, actorId, tenantId, submissionId, expectedStatus);
    }

    @Override
    public void insertAction(WorkflowRuntimeService.ActionLog action, UUID actorId) {
        jdbc.update("""
                insert into workflow.wf_action_log(
                    id,tenant_id,instance_id,task_id,action_code,from_status,to_status,operator_id,
                    operator_identity_id,reason,occurred_at,request_id,snapshot_hash,created_by,updated_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, action.id(), action.tenantId(), action.instanceId(), action.taskId(), action.actionCode(),
                action.fromStatus(), action.toStatus(), action.operatorId(), action.operatorIdentityId(), action.reason(),
                timestamp(action.occurredAt()), action.requestId(), action.snapshotHash(), actorId, actorId);
    }

    private WorkflowFormService.FormDefinition mapForm(ResultSet rs) throws SQLException {
        return new WorkflowFormService.FormDefinition(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("form_code"),
                rs.getString("form_name"), rs.getString("process_code"), rs.getString("node_code"), rs.getInt("version_no"),
                parse(rs.getString("field_schema")), parse(rs.getString("layout_schema")), parse(rs.getString("validation_schema")),
                parse(rs.getString("visibility_matrix")), parse(rs.getString("edit_matrix")), rs.getBoolean("enabled"));
    }

    private WorkflowFormService.Submission mapSubmission(ResultSet rs) throws SQLException {
        return new WorkflowFormService.Submission(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("submission_no"),
                rs.getObject("instance_id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("form_definition_id", UUID.class), rs.getInt("form_version"),
                rs.getObject("submitter_id", UUID.class), rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("content_hash"), rs.getString("status"));
    }

    private String formSelect(String suffix) {
        return """
                select id,tenant_id,form_code,form_name,process_code,node_code,version_no,field_schema,layout_schema,
                       validation_schema,visibility_matrix,edit_matrix,enabled
                from workflow.wf_form_definition
                """ + suffix;
    }

    private String submissionSelect(String suffix) {
        return """
                select id,tenant_id,submission_no,instance_id,task_id,form_definition_id,form_version,
                       submitter_id,submitted_at,content_hash,status
                from workflow.wf_submission
                """ + suffix;
    }

    private String json(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new WorkflowException(WorkflowException.Code.INVALID_ARGUMENT, "invalid workflow form JSON", ex);
        }
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new WorkflowException(WorkflowException.Code.INVALID_DEFINITION, "invalid persisted workflow form JSON", ex);
        }
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
