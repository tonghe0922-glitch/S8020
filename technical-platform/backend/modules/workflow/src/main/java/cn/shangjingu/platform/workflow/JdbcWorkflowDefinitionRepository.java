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
public class JdbcWorkflowDefinitionRepository implements WorkflowDefinitionService.Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowDefinitionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insertDefinition(WorkflowDefinitionService.Definition definition, UUID actorId) {
        jdbc.update("""
                insert into workflow.wf_definition(
                    id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled,created_by,updated_by)
                values (?,?,?,?,?,?,?,?,?,?)
                """, definition.id(), definition.tenantId(), definition.processCode(), definition.processName(),
                definition.moduleCode(), definition.ownerSchema(), definition.ownerTable(), definition.enabled(), actorId, actorId);
    }

    @Override
    public Optional<WorkflowDefinitionService.Definition> lockDefinition(UUID tenantId, UUID definitionId) {
        return jdbc.query("""
                select id,tenant_id,process_code,process_name,module_code,owner_schema,owner_table,enabled
                from workflow.wf_definition
                where tenant_id=? and id=? and not is_deleted
                for update
                """, (rs, row) -> mapDefinition(rs), tenantId, definitionId).stream().findFirst();
    }

    @Override
    public int nextVersionNo(UUID tenantId, UUID definitionId) {
        Integer next = jdbc.queryForObject("""
                select coalesce(max(version_no),0)+1
                from workflow.wf_version
                where tenant_id=? and definition_id=? and not is_deleted
                """, Integer.class, tenantId, definitionId);
        return next == null ? 1 : next;
    }

    @Override
    public void insertVersion(WorkflowDefinitionService.Version version, UUID actorId) {
        jdbc.update("""
                insert into workflow.wf_version(
                    id,tenant_id,definition_id,version_no,status,effective_at,definition_json,checksum,created_by,updated_by)
                values (?,?,?,?,?,?,?::jsonb,?,?,?)
                """, version.id(), version.tenantId(), version.definitionId(), version.versionNo(), version.status(),
                timestamp(version.effectiveAt()), json(version.definitionJson()), version.checksum(), actorId, actorId);
    }

    @Override
    public Optional<WorkflowDefinitionService.Version> lockVersion(UUID tenantId, UUID versionId) {
        return jdbc.query("""
                select id,tenant_id,definition_id,version_no,status,effective_at,definition_json,checksum
                from workflow.wf_version
                where tenant_id=? and id=? and not is_deleted
                for update
                """, (rs, row) -> mapVersion(rs), tenantId, versionId).stream().findFirst();
    }

    @Override
    public Optional<WorkflowDefinitionService.Version> findVersion(UUID tenantId, UUID versionId) {
        return jdbc.query("""
                select id,tenant_id,definition_id,version_no,status,effective_at,definition_json,checksum
                from workflow.wf_version
                where tenant_id=? and id=? and not is_deleted
                """, (rs, row) -> mapVersion(rs), tenantId, versionId).stream().findFirst();
    }

    @Override
    public void insertNode(WorkflowDefinitionService.Node node, UUID actorId) {
        jdbc.update("""
                insert into workflow.wf_node(
                    id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sla_policy_id,sort_no,created_by,updated_by)
                values (?,?,?,?,?,?,?::jsonb,?,?,?,?)
                """, node.id(), node.tenantId(), node.versionId(), node.nodeCode(), node.nodeName(), node.nodeType(),
                json(node.actorRule()), node.slaPolicyId(), node.sortNo(), actorId, actorId);
    }

    @Override
    public boolean nodeExists(UUID tenantId, UUID versionId, String nodeCode) {
        Integer found = jdbc.queryForObject("""
                select count(*) from workflow.wf_node
                where tenant_id=? and version_id=? and node_code=? and not is_deleted
                """, Integer.class, tenantId, versionId, nodeCode);
        return found != null && found == 1;
    }

    @Override
    public List<WorkflowDefinitionService.Node> listNodes(UUID tenantId, UUID versionId) {
        return jdbc.query("""
                select id,tenant_id,version_id,node_code,node_name,node_type,actor_rule,sla_policy_id,sort_no
                from workflow.wf_node
                where tenant_id=? and version_id=? and not is_deleted
                order by sort_no,node_code
                """, (rs, row) -> mapNode(rs), tenantId, versionId);
    }

    @Override
    public void insertTransition(WorkflowDefinitionService.Transition transition, UUID actorId) {
        jdbc.update("""
                insert into workflow.wf_transition(
                    id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback,created_by,updated_by)
                values (?,?,?,?,?,?,?::jsonb,?,?,?)
                """, transition.id(), transition.tenantId(), transition.versionId(), transition.fromNodeCode(),
                transition.actionCode(), transition.toNodeCode(), json(transition.conditionExpr()), transition.rollback(), actorId, actorId);
    }

    @Override
    public List<WorkflowDefinitionService.Transition> listTransitions(UUID tenantId, UUID versionId) {
        return jdbc.query("""
                select id,tenant_id,version_id,from_node_code,action_code,to_node_code,condition_expr,is_rollback
                from workflow.wf_transition
                where tenant_id=? and version_id=? and not is_deleted
                order by from_node_code,action_code,to_node_code,id
                """, (rs, row) -> mapTransition(rs), tenantId, versionId);
    }

    @Override
    public int publishVersion(UUID tenantId, UUID versionId, UUID actorId, Instant effectiveAt, String checksum) {
        return jdbc.update("""
                update workflow.wf_version
                set status='PUBLISHED',effective_at=?,checksum=?,updated_by=?
                where tenant_id=? and id=? and status='DRAFT' and not is_deleted
                """, timestamp(effectiveAt), checksum, actorId, tenantId, versionId);
    }

    private WorkflowDefinitionService.Definition mapDefinition(ResultSet rs) throws SQLException {
        return new WorkflowDefinitionService.Definition(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("process_code"),
                rs.getString("process_name"), rs.getString("module_code"), rs.getString("owner_schema"),
                rs.getString("owner_table"), rs.getBoolean("enabled"));
    }

    private WorkflowDefinitionService.Version mapVersion(ResultSet rs) throws SQLException {
        Timestamp effectiveAt = rs.getTimestamp("effective_at");
        return new WorkflowDefinitionService.Version(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("definition_id", UUID.class), rs.getInt("version_no"), rs.getString("status"),
                effectiveAt == null ? null : effectiveAt.toInstant(), parse(rs.getString("definition_json")), rs.getString("checksum"));
    }

    private WorkflowDefinitionService.Node mapNode(ResultSet rs) throws SQLException {
        return new WorkflowDefinitionService.Node(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getString("node_code"), rs.getString("node_name"), rs.getString("node_type"), parse(rs.getString("actor_rule")),
                rs.getObject("sla_policy_id", UUID.class), rs.getInt("sort_no"));
    }

    private WorkflowDefinitionService.Transition mapTransition(ResultSet rs) throws SQLException {
        return new WorkflowDefinitionService.Transition(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getString("from_node_code"), rs.getString("action_code"), rs.getString("to_node_code"),
                parse(rs.getString("condition_expr")), rs.getBoolean("is_rollback"));
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
            throw new WorkflowException(WorkflowException.Code.INVALID_DEFINITION, "invalid persisted workflow JSON", ex);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
