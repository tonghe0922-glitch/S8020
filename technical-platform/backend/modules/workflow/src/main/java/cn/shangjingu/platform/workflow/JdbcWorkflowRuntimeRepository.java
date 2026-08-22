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
public class JdbcWorkflowRuntimeRepository implements WorkflowRuntimeService.Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowRuntimeRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<WorkflowRuntimeService.RuntimeVersion> findPublishedVersion(UUID tenantId, UUID versionId) {
        return jdbc
                .query(
                        """
                select d.id definition_id,v.id version_id,d.process_code,v.status
                from workflow.wf_version v
                join workflow.wf_definition d
                  on d.tenant_id=v.tenant_id and d.id=v.definition_id and not d.is_deleted and d.enabled
                where v.tenant_id=? and v.id=? and v.status='PUBLISHED' and not v.is_deleted
                  and (v.effective_at is null or v.effective_at <= now())
                """,
                        (rs, row) -> new WorkflowRuntimeService.RuntimeVersion(
                                rs.getObject("definition_id", UUID.class), rs.getObject("version_id", UUID.class),
                                rs.getString("process_code"), rs.getString("status")),
                        tenantId,
                        versionId)
                .stream()
                .findFirst();
    }

    @Override
    public List<WorkflowRuntimeService.RuntimeNode> listNodesByType(UUID tenantId, UUID versionId, String nodeType) {
        return jdbc.query(
                """
                select node_code,node_name,node_type,actor_rule,sla_policy_id,sort_no
                from workflow.wf_node
                where tenant_id=? and version_id=? and upper(node_type)=upper(?) and not is_deleted
                order by sort_no,node_code
                """,
                (rs, row) -> mapNode(rs),
                tenantId,
                versionId,
                nodeType);
    }

    @Override
    public Optional<WorkflowRuntimeService.RuntimeNode> findNode(UUID tenantId, UUID versionId, String nodeCode) {
        return jdbc
                .query(
                        """
                select node_code,node_name,node_type,actor_rule,sla_policy_id,sort_no
                from workflow.wf_node
                where tenant_id=? and version_id=? and node_code=? and not is_deleted
                """,
                        (rs, row) -> mapNode(rs),
                        tenantId,
                        versionId,
                        nodeCode)
                .stream()
                .findFirst();
    }

    @Override
    public List<WorkflowRuntimeService.RuntimeTransition> listTransitions(
            UUID tenantId, UUID versionId, String fromNodeCode, String actionCode) {
        return jdbc.query(
                """
                select from_node_code,action_code,to_node_code,condition_expr,is_rollback
                from workflow.wf_transition
                where tenant_id=? and version_id=? and from_node_code=? and action_code=? and not is_deleted
                order by id
                """,
                (rs, row) -> new WorkflowRuntimeService.RuntimeTransition(
                        rs.getString("from_node_code"),
                        rs.getString("action_code"),
                        rs.getString("to_node_code"),
                        parse(rs.getString("condition_expr")),
                        rs.getBoolean("is_rollback")),
                tenantId,
                versionId,
                fromNodeCode,
                actionCode);
    }

    @Override
    public void insertInstance(WorkflowRuntimeService.Instance instance, UUID actorId) {
        jdbc.update(
                """
                insert into workflow.wf_instance(
                    id,tenant_id,instance_no,definition_id,version_id,process_code,
                    business_object_type,business_object_id,business_object_no,title,initiator_id,
                    current_node_code,status,priority,started_at,finished_at,due_at,context_snapshot,created_by,updated_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                """,
                instance.id(),
                instance.tenantId(),
                instance.instanceNo(),
                instance.definitionId(),
                instance.versionId(),
                instance.processCode(),
                instance.businessObjectType(),
                instance.businessObjectId(),
                instance.businessObjectNo(),
                instance.title(),
                instance.initiatorId(),
                instance.currentNodeCode(),
                instance.status(),
                instance.priority(),
                timestamp(instance.startedAt()),
                timestamp(instance.finishedAt()),
                timestamp(instance.dueAt()),
                json(instance.contextSnapshot()),
                actorId,
                actorId);
    }

    @Override
    public Optional<WorkflowRuntimeService.Instance> findInstance(UUID tenantId, UUID instanceId) {
        return jdbc
                .query(
                        instanceSelect("where tenant_id=? and id=? and not is_deleted"),
                        (rs, row) -> mapInstance(rs),
                        tenantId,
                        instanceId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<WorkflowRuntimeService.Instance> lockInstance(UUID tenantId, UUID instanceId) {
        return jdbc
                .query(
                        instanceSelect("where tenant_id=? and id=? and not is_deleted for update"),
                        (rs, row) -> mapInstance(rs),
                        tenantId,
                        instanceId)
                .stream()
                .findFirst();
    }

    @Override
    public int moveInstance(
            UUID tenantId,
            UUID instanceId,
            String expectedNodeCode,
            String targetNodeCode,
            String status,
            Instant finishedAt,
            UUID actorId) {
        return jdbc.update(
                """
                update workflow.wf_instance
                set current_node_code=?,status=?,finished_at=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and current_node_code=? and status='RUNNING' and not is_deleted
                """,
                targetNodeCode,
                status,
                timestamp(finishedAt),
                actorId,
                tenantId,
                instanceId,
                expectedNodeCode);
    }

    @Override
    public void insertTask(WorkflowRuntimeService.Task task, UUID actorId) {
        jdbc.update(
                """
                insert into workflow.wf_task(
                    id,tenant_id,instance_id,task_no,node_code,task_type,assignee_id,candidate_rule,
                    status,received_at,due_at,completed_at,result_code,comment,created_by,updated_by)
                values (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?)
                """,
                task.id(),
                task.tenantId(),
                task.instanceId(),
                task.taskNo(),
                task.nodeCode(),
                task.taskType(),
                task.assigneeId(),
                json(task.candidateRule()),
                task.status(),
                timestamp(task.receivedAt()),
                timestamp(task.dueAt()),
                timestamp(task.completedAt()),
                task.resultCode(),
                task.comment(),
                actorId,
                actorId);
    }

    @Override
    public Optional<WorkflowRuntimeService.Task> findCurrentTask(UUID tenantId, UUID instanceId, String nodeCode) {
        if (nodeCode == null) return Optional.empty();
        return jdbc
                .query(
                        taskSelect(
                                """
                where tenant_id=? and instance_id=? and node_code=? and status='PENDING' and not is_deleted
                order by received_at desc,id desc limit 1
                """),
                        (rs, row) -> mapTask(rs),
                        tenantId,
                        instanceId,
                        nodeCode)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<WorkflowRuntimeService.Task> lockTask(UUID tenantId, UUID taskId) {
        return jdbc
                .query(
                        taskSelect("where tenant_id=? and id=? and not is_deleted for update"),
                        (rs, row) -> mapTask(rs),
                        tenantId,
                        taskId)
                .stream()
                .findFirst();
    }

    @Override
    public int completeTask(
            UUID tenantId, UUID taskId, UUID actorId, String resultCode, String comment, Instant completedAt) {
        return jdbc.update(
                """
                update workflow.wf_task
                set status='COMPLETED',completed_at=?,result_code=?,comment=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and status='PENDING' and completed_at is null and not is_deleted
                """,
                timestamp(completedAt),
                resultCode,
                comment,
                actorId,
                tenantId,
                taskId);
    }

    @Override
    public void insertAction(WorkflowRuntimeService.ActionLog action, UUID actorId) {
        jdbc.update(
                """
                insert into workflow.wf_action_log(
                    id,tenant_id,instance_id,task_id,action_code,from_status,to_status,operator_id,
                    operator_identity_id,reason,occurred_at,request_id,snapshot_hash,created_by,updated_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                action.id(),
                action.tenantId(),
                action.instanceId(),
                action.taskId(),
                action.actionCode(),
                action.fromStatus(),
                action.toStatus(),
                action.operatorId(),
                action.operatorIdentityId(),
                action.reason(),
                timestamp(action.occurredAt()),
                action.requestId(),
                action.snapshotHash(),
                actorId,
                actorId);
    }

    @Override
    public Optional<WorkflowRuntimeService.ActionLog> findAction(UUID tenantId, UUID actionId) {
        return jdbc
                .query(
                        actionSelect("where tenant_id=? and id=? and not is_deleted"),
                        (rs, row) -> mapAction(rs),
                        tenantId,
                        actionId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<WorkflowRuntimeService.ActionLog> findActionByRequestId(UUID tenantId, String requestId) {
        return jdbc
                .query(
                        actionSelect("where tenant_id=? and request_id=? and not is_deleted"),
                        (rs, row) -> mapAction(rs),
                        tenantId,
                        requestId)
                .stream()
                .findFirst();
    }

    private WorkflowRuntimeService.RuntimeNode mapNode(ResultSet rs) throws SQLException {
        return new WorkflowRuntimeService.RuntimeNode(
                rs.getString("node_code"),
                rs.getString("node_name"),
                rs.getString("node_type"),
                parse(rs.getString("actor_rule")),
                rs.getObject("sla_policy_id", UUID.class),
                rs.getInt("sort_no"));
    }

    private WorkflowRuntimeService.Instance mapInstance(ResultSet rs) throws SQLException {
        return new WorkflowRuntimeService.Instance(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("instance_no"),
                rs.getObject("definition_id", UUID.class),
                rs.getObject("version_id", UUID.class),
                rs.getString("process_code"),
                rs.getString("business_object_type"),
                rs.getObject("business_object_id", UUID.class),
                rs.getString("business_object_no"),
                rs.getString("title"),
                rs.getObject("initiator_id", UUID.class),
                rs.getString("current_node_code"),
                rs.getString("status"),
                rs.getString("priority"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "due_at"),
                parse(rs.getString("context_snapshot")));
    }

    private WorkflowRuntimeService.Task mapTask(ResultSet rs) throws SQLException {
        return new WorkflowRuntimeService.Task(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("instance_id", UUID.class),
                rs.getString("task_no"),
                rs.getString("node_code"),
                rs.getString("task_type"),
                rs.getObject("assignee_id", UUID.class),
                parse(rs.getString("candidate_rule")),
                rs.getString("status"),
                instant(rs, "received_at"),
                instant(rs, "due_at"),
                instant(rs, "completed_at"),
                rs.getString("result_code"),
                rs.getString("comment"));
    }

    private WorkflowRuntimeService.ActionLog mapAction(ResultSet rs) throws SQLException {
        return new WorkflowRuntimeService.ActionLog(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("instance_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getString("action_code"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getObject("operator_id", UUID.class),
                rs.getObject("operator_identity_id", UUID.class),
                rs.getString("reason"),
                instant(rs, "occurred_at"),
                rs.getString("request_id"),
                rs.getString("snapshot_hash"));
    }

    private String instanceSelect(String suffix) {
        return """
                select id,tenant_id,instance_no,definition_id,version_id,process_code,business_object_type,
                       business_object_id,business_object_no,title,initiator_id,current_node_code,status,priority,
                       started_at,finished_at,due_at,context_snapshot
                from workflow.wf_instance
                """
                + suffix;
    }

    private String taskSelect(String suffix) {
        return """
                select id,tenant_id,instance_id,task_no,node_code,task_type,assignee_id,candidate_rule,status,
                       received_at,due_at,completed_at,result_code,comment
                from workflow.wf_task
                """
                + suffix;
    }

    private String actionSelect(String suffix) {
        return """
                select id,tenant_id,instance_id,task_id,action_code,from_status,to_status,operator_id,
                       operator_identity_id,reason,occurred_at,request_id,snapshot_hash
                from workflow.wf_action_log
                """
                + suffix;
    }

    private String json(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new WorkflowException(WorkflowException.Code.INVALID_ARGUMENT, "invalid workflow JSON", ex);
        }
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new WorkflowException(
                    WorkflowException.Code.INVALID_DEFINITION, "invalid persisted workflow JSON", ex);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
