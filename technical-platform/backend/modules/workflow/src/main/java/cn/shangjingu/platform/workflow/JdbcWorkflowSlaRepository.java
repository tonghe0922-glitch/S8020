package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkflowSlaRepository implements WorkflowSlaService.Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcWorkflowSlaRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<WorkflowSlaService.SlaTask> findTask(UUID tenantId, UUID taskId) {
        return jdbc.query(taskSql("where tenant_id=? and id=? and not is_deleted"),
                (rs, row) -> task(rs), tenantId, taskId).stream().findFirst();
    }

    @Override
    public Optional<WorkflowSlaService.SlaInstance> lockInstance(UUID tenantId, UUID instanceId) {
        return jdbc.query(instanceSql("where tenant_id=? and id=? and not is_deleted for update"),
                (rs, row) -> instance(rs), tenantId, instanceId).stream().findFirst();
    }

    @Override
    public Optional<WorkflowSlaService.SlaTask> lockTask(UUID tenantId, UUID taskId) {
        return jdbc.query(taskSql("where tenant_id=? and id=? and not is_deleted for update"),
                (rs, row) -> task(rs), tenantId, taskId).stream().findFirst();
    }

    @Override
    public Optional<UUID> findNodeSlaPolicyId(UUID tenantId, UUID versionId, String nodeCode) {
        return jdbc.query("""
                select sla_policy_id
                from workflow.wf_node
                where tenant_id=? and version_id=? and node_code=? and not is_deleted
                """, (rs, row) -> rs.getObject("sla_policy_id", UUID.class), tenantId, versionId, nodeCode)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public Optional<WorkflowSlaService.SlaPolicy> findPolicy(UUID tenantId, UUID policyId) {
        return jdbc.query("""
                select id,policy_code,process_code,node_code,duration_minutes,calendar_id,remind_rules,escalation_rules
                from workflow.wf_sla_policy
                where tenant_id=? and id=? and not is_deleted
                """, (rs, row) -> new WorkflowSlaService.SlaPolicy(
                        rs.getObject("id", UUID.class), rs.getString("policy_code"), rs.getString("process_code"),
                        rs.getString("node_code"), rs.getInt("duration_minutes"), rs.getObject("calendar_id", UUID.class),
                        parse(rs.getString("remind_rules")), parse(rs.getString("escalation_rules"))),
                tenantId, policyId).stream().findFirst();
    }

    @Override
    public int updateTaskDueAt(UUID tenantId, UUID taskId, Instant dueAt, UUID actorId) {
        return jdbc.update("""
                update workflow.wf_task
                set due_at=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and status='PENDING' and not is_deleted
                """, timestamp(dueAt), actorId, tenantId, taskId);
    }

    @Override
    public int updateInstanceDueAt(UUID tenantId, UUID instanceId, Instant dueAt, UUID actorId) {
        return jdbc.update("""
                update workflow.wf_instance
                set due_at=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and status='RUNNING' and not is_deleted
                """, timestamp(dueAt), actorId, tenantId, instanceId);
    }

    @Override
    public Optional<WorkflowSlaService.ActionEvidence> findFirstAction(UUID tenantId, UUID taskId, String actionCode) {
        return jdbc.query(actionSql("""
                where tenant_id=? and task_id=? and action_code=? and not is_deleted
                order by occurred_at,id limit 1
                """), (rs, row) -> action(rs), tenantId, taskId, actionCode).stream().findFirst();
    }

    @Override
    public Optional<WorkflowSlaService.ActionEvidence> findLatestLifecycleAction(UUID tenantId, UUID taskId) {
        return jdbc.query(actionSql("""
                where tenant_id=? and task_id=? and action_code in ('SLA_STARTED','SLA_PAUSED','SLA_RESUMED') and not is_deleted
                order by occurred_at desc,id desc limit 1
                """), (rs, row) -> action(rs), tenantId, taskId).stream().findFirst();
    }

    @Override
    public Optional<WorkflowSlaService.ActionEvidence> findActionByRequestId(UUID tenantId, String requestId) {
        return jdbc.query(actionSql("""
                where tenant_id=? and request_id=? and not is_deleted
                order by occurred_at desc,id desc limit 1
                """), (rs, row) -> action(rs), tenantId, requestId).stream().findFirst();
    }

    @Override
    public void insertAction(WorkflowSlaService.ActionEvidence action, UUID actorId) {
        jdbc.update("""
                insert into workflow.wf_action_log(
                    id,tenant_id,instance_id,task_id,action_code,from_status,to_status,
                    operator_id,reason,occurred_at,request_id,snapshot_hash,created_by,updated_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, action.id(), action.tenantId(), action.instanceId(), action.taskId(), action.actionCode(),
                action.fromStatus(), action.toStatus(), action.operatorId(), action.reason(), timestamp(action.occurredAt()),
                action.requestId(), action.snapshotHash(), actorId, actorId);
    }

    private WorkflowSlaService.SlaTask task(ResultSet rs) throws SQLException {
        return new WorkflowSlaService.SlaTask(
                rs.getObject("id", UUID.class), rs.getObject("instance_id", UUID.class), rs.getString("node_code"),
                rs.getObject("assignee_id", UUID.class), rs.getString("status"), instant(rs, "received_at"), instant(rs, "due_at"));
    }

    private WorkflowSlaService.SlaInstance instance(ResultSet rs) throws SQLException {
        return new WorkflowSlaService.SlaInstance(
                rs.getObject("id", UUID.class), rs.getObject("version_id", UUID.class), rs.getString("process_code"),
                rs.getString("current_node_code"), rs.getString("status"), instant(rs, "due_at"),
                parse(rs.getString("context_snapshot")));
    }

    private WorkflowSlaService.ActionEvidence action(ResultSet rs) throws SQLException {
        return new WorkflowSlaService.ActionEvidence(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("instance_id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getString("action_code"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getObject("operator_id", UUID.class), rs.getString("reason"),
                instant(rs, "occurred_at"), rs.getString("request_id"), rs.getString("snapshot_hash"));
    }

    private String taskSql(String suffix) {
        return """
                select id,instance_id,node_code,assignee_id,status,received_at,due_at
                from workflow.wf_task
                """ + suffix;
    }

    private String instanceSql(String suffix) {
        return """
                select id,version_id,process_code,current_node_code,status,due_at,context_snapshot
                from workflow.wf_instance
                """ + suffix;
    }

    private String actionSql(String suffix) {
        return """
                select id,tenant_id,instance_id,task_id,action_code,from_status,to_status,operator_id,
                       reason,occurred_at,request_id,snapshot_hash
                from workflow.wf_action_log
                """ + suffix;
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new WorkflowException(WorkflowException.Code.INVALID_DEFINITION, "invalid persisted SLA JSON", ex);
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
