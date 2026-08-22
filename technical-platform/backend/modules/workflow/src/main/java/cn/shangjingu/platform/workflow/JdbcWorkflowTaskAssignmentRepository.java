package cn.shangjingu.platform.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkflowTaskAssignmentRepository implements WorkflowTaskAssignmentService.Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowTaskAssignmentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<WorkflowTaskAssignmentService.CandidateTask> findTask(UUID tenantId, UUID taskId) {
        return jdbc
                .query(
                        taskSelect("where tenant_id=? and id=? and not is_deleted"),
                        (rs, row) -> mapTask(rs),
                        tenantId,
                        taskId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<WorkflowTaskAssignmentService.CandidateInstance> lockInstance(UUID tenantId, UUID instanceId) {
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
    public Optional<WorkflowTaskAssignmentService.CandidateTask> lockTask(UUID tenantId, UUID taskId) {
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
    public int claimTask(UUID tenantId, UUID taskId, UUID assigneeId, UUID actorId) {
        return jdbc.update(
                """
                update workflow.wf_task
                set assignee_id=?,updated_by=?,updated_at=now()
                where tenant_id=? and id=? and status='PENDING' and assignee_id is null and not is_deleted
                """,
                assigneeId,
                actorId,
                tenantId,
                taskId);
    }

    private WorkflowTaskAssignmentService.CandidateTask mapTask(ResultSet rs) throws SQLException {
        return new WorkflowTaskAssignmentService.CandidateTask(
                rs.getObject("id", UUID.class),
                rs.getObject("instance_id", UUID.class),
                rs.getString("node_code"),
                rs.getObject("assignee_id", UUID.class),
                parse(rs.getString("candidate_rule")),
                rs.getString("status"));
    }

    private WorkflowTaskAssignmentService.CandidateInstance mapInstance(ResultSet rs) throws SQLException {
        return new WorkflowTaskAssignmentService.CandidateInstance(
                rs.getObject("id", UUID.class),
                rs.getObject("initiator_id", UUID.class),
                rs.getString("current_node_code"),
                rs.getString("status"),
                parse(rs.getString("context_snapshot")));
    }

    private String taskSelect(String suffix) {
        return """
                select id,instance_id,node_code,assignee_id,candidate_rule,status
                from workflow.wf_task
                """
                + suffix;
    }

    private String instanceSelect(String suffix) {
        return """
                select id,initiator_id,current_node_code,status,context_snapshot
                from workflow.wf_instance
                """
                + suffix;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new WorkflowException(
                    WorkflowException.Code.INVALID_DEFINITION, "stored workflow JSON cannot be parsed", ex);
        }
    }
}
