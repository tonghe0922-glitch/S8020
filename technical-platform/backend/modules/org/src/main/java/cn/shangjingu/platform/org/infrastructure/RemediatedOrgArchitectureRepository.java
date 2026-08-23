package cn.shangjingu.platform.org.infrastructure;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Primary
@Repository
public class RemediatedOrgArchitectureRepository extends JdbcOrgArchitectureRepository {
    private final JdbcTemplate jdbc;

    public RemediatedOrgArchitectureRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
        this.jdbc = jdbc;
    }

    @Override
    public NodeView createNode(UUID tenantId, UUID actorId, NodeCommand command) {
        UUID nodeId = UUID.randomUUID();
        String orgCode = command.orgCode().trim();
        String path = pathFor(tenantId, command.parentId(), orgCode);
        jdbc.update(
                """
                insert into org.organization(
                    id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,
                    org_code,org_name,org_type,parent_id,path,manager_employee_id,status,
                    description,headcount_plan,sort_no,version_no)
                values (?,?,?,now(),?,now(),false,?,?,?,?,cast(? as ltree),?,?,?,?,?,0)
                """,
                nodeId,
                tenantId,
                actorId,
                actorId,
                orgCode,
                command.orgName().trim(),
                normalized(command.orgType()),
                command.parentId(),
                path,
                command.managerEmployeeId(),
                normalized(command.status()),
                trimToNull(command.description()),
                command.headcountPlan(),
                command.sortNo());
        return requiredNode(tenantId, nodeId);
    }

    @Override
    public NodeView updateNode(UUID tenantId, UUID actorId, UUID nodeId, NodeCommand command) {
        NodeView before = requiredNode(tenantId, nodeId);
        if (before.versionNo() != command.expectedVersion()) {
            throw new OptimisticLockingFailureException("组织节点版本已过期，请刷新后重试");
        }
        validateParent(tenantId, nodeId, before.path(), command.parentId());
        String newPath = pathFor(tenantId, command.parentId(), before.orgCode());
        if (!before.path().equals(newPath)) {
            jdbc.update(
                    """
                    update org.organization
                       set path=cast(? as ltree) || subpath(path,nlevel(cast(? as ltree))),
                           updated_by=?,updated_at=now(),version_no=version_no+1
                     where tenant_id=? and id<>? and not is_deleted and path <@ cast(? as ltree)
                    """,
                    newPath,
                    before.path(),
                    actorId,
                    tenantId,
                    nodeId,
                    before.path());
        }
        int updated = jdbc.update(
                """
                update org.organization
                   set org_name=?,org_type=?,parent_id=?,path=cast(? as ltree),
                       manager_employee_id=?,status=?,description=?,headcount_plan=?,sort_no=?,
                       updated_by=?,updated_at=now(),version_no=version_no+1
                 where tenant_id=? and id=? and not is_deleted and version_no=?
                """,
                command.orgName().trim(),
                normalized(command.orgType()),
                command.parentId(),
                newPath,
                command.managerEmployeeId(),
                normalized(command.status()),
                trimToNull(command.description()),
                command.headcountPlan(),
                command.sortNo(),
                actorId,
                tenantId,
                nodeId,
                command.expectedVersion());
        if (updated != 1) {
            throw new OptimisticLockingFailureException("组织节点版本已过期，请刷新后重试");
        }
        return requiredNode(tenantId, nodeId);
    }

    @Override
    public void applySnapshot(UUID tenantId, UUID actorId, List<NodeView> snapshot) {
        List<NodeView> current = tree(tenantId);
        Set<UUID> proposedIds = new HashSet<>();
        snapshot.forEach(node -> proposedIds.add(node.id()));
        for (NodeView existing : current) {
            if (proposedIds.contains(existing.id())) continue;
            Integer members = jdbc.queryForObject(
                    """
                    select count(*) from org.employee_position
                    where tenant_id=? and org_id=? and not is_deleted and status='ACTIVE'
                      and effective_start_date<=current_date
                      and (effective_end_date is null or effective_end_date>=current_date)
                    """,
                    Integer.class,
                    tenantId,
                    existing.id());
            if (members != null && members > 0) {
                throw new IllegalArgumentException("组织“" + existing.orgName() + "”存在在岗成员，不能从正式架构中移除");
            }
            jdbc.update(
                    """
                    update org.organization
                       set status='INACTIVE',is_deleted=true,deleted_at=now(),updated_by=?,updated_at=now(),
                           version_no=version_no+1
                     where tenant_id=? and id=? and not is_deleted
                    """,
                    actorId,
                    tenantId,
                    existing.id());
        }
        for (NodeView node : snapshot) {
            Optional<NodeView> existing = node(tenantId, node.id());
            String orgCode = existing.map(NodeView::orgCode).orElse(node.orgCode());
            int updated = jdbc.update(
                    """
                    update org.organization
                       set org_name=?,org_type=?,parent_id=?,path=cast(? as ltree),status=?,
                           manager_employee_id=?,description=?,headcount_plan=?,sort_no=?,updated_by=?,updated_at=now(),
                           is_deleted=false,deleted_at=null,version_no=version_no+1
                     where tenant_id=? and id=?
                    """,
                    node.orgName(),
                    node.orgType(),
                    node.parentId(),
                    node.path(),
                    node.status(),
                    node.managerEmployeeId(),
                    trimToNull(node.description()),
                    node.headcountPlan(),
                    node.sortNo(),
                    actorId,
                    tenantId,
                    node.id());
            if (updated == 0) {
                insertSnapshotNode(tenantId, actorId, node, orgCode);
            }
        }
    }

    private void insertSnapshotNode(UUID tenantId, UUID actorId, NodeView node, String orgCode) {
        jdbc.update(
                """
                insert into org.organization(
                    id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,
                    org_code,org_name,org_type,parent_id,path,manager_employee_id,status,
                    description,headcount_plan,sort_no,version_no)
                values (?,?,?,now(),?,now(),false,?,?,?,?,cast(? as ltree),?,?,?,?,?,0)
                """,
                node.id(),
                tenantId,
                actorId,
                actorId,
                orgCode,
                node.orgName(),
                node.orgType(),
                node.parentId(),
                node.path(),
                node.managerEmployeeId(),
                node.status(),
                trimToNull(node.description()),
                node.headcountPlan(),
                node.sortNo());
    }

    private NodeView requiredNode(UUID tenantId, UUID nodeId) {
        return node(tenantId, nodeId).orElseThrow(() -> new IllegalArgumentException("组织节点不存在"));
    }

    private String pathFor(UUID tenantId, UUID parentId, String orgCode) {
        String label = pathLabel(orgCode);
        if (parentId == null) return label;
        return requiredNode(tenantId, parentId).path() + "." + label;
    }

    private void validateParent(UUID tenantId, UUID nodeId, String oldPath, UUID parentId) {
        if (parentId == null) return;
        if (nodeId.equals(parentId)) throw new IllegalArgumentException("组织不能将自身设为上级组织");
        NodeView parent = requiredNode(tenantId, parentId);
        Boolean descendant = jdbc.queryForObject(
                "select cast(? as ltree) <@ cast(? as ltree)", Boolean.class, parent.path(), oldPath);
        if (Boolean.TRUE.equals(descendant)) {
            throw new IllegalArgumentException("组织不能移动到自身下级组织之下");
        }
    }

    private static String normalized(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String pathLabel(String code) {
        String normalized = code.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) normalized = "org";
        if (Character.isDigit(normalized.charAt(0))) normalized = "o_" + normalized;
        return normalized;
    }
}
