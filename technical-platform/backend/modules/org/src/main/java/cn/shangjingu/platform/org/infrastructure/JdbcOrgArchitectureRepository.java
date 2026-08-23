package cn.shangjingu.platform.org.infrastructure;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;

import cn.shangjingu.platform.org.application.OrgArchitectureRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrgArchitectureRepository implements OrgArchitectureRepository {
    private static final TypeReference<List<NodeView>> NODE_LIST = new TypeReference<>() {};
    private static final TypeReference<List<ChangeLine>> CHANGE_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcOrgArchitectureRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<NodeView> tree(UUID tenantId) {
        return jdbc.query(
                """
                select o.id,o.org_code,o.org_name,o.org_type,o.parent_id,o.path::text,o.status,
                       o.manager_employee_id,o.headcount_plan,o.sort_no,o.version_no,o.description,
                       (select count(*) from org.employee_position ep
                         where ep.tenant_id=o.tenant_id and ep.org_id=o.id and not ep.is_deleted
                           and ep.status='ACTIVE' and ep.effective_start_date<=current_date
                           and (ep.effective_end_date is null or ep.effective_end_date>=current_date)) member_count
                from org.organization o
                where o.tenant_id=? and not o.is_deleted
                order by o.path,o.sort_no,o.org_code,o.id
                """,
                (rs, rowNum) -> node(rs),
                tenantId);
    }

    @Override
    public Optional<NodeView> node(UUID tenantId, UUID nodeId) {
        return jdbc
                .query(
                        """
                        select o.id,o.org_code,o.org_name,o.org_type,o.parent_id,o.path::text,o.status,
                               o.manager_employee_id,o.headcount_plan,o.sort_no,o.version_no,o.description,
                               (select count(*) from org.employee_position ep
                                 where ep.tenant_id=o.tenant_id and ep.org_id=o.id and not ep.is_deleted
                                   and ep.status='ACTIVE' and ep.effective_start_date<=current_date
                                   and (ep.effective_end_date is null or ep.effective_end_date>=current_date)) member_count
                        from org.organization o
                        where o.tenant_id=? and o.id=? and not o.is_deleted
                        """,
                        (rs, rowNum) -> node(rs),
                        tenantId,
                        nodeId)
                .stream()
                .findFirst();
    }

    @Override
    public NodeView createNode(UUID tenantId, UUID actorId, NodeCommand command) {
        UUID nodeId = UUID.randomUUID();
        String path = pathFor(tenantId, command.parentId(), command.orgCode());
        jdbc.update(
                """
                insert into org.organization(
                    id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,
                    org_code,org_name,org_type,parent_id,path,manager_employee_id,status,
                    description,headcount_plan,sort_no,version_no)
                values (?,?,?,?,?,?,false,?,?,?,?,cast(? as ltree),?,?,?,?,?,0)
                """,
                nodeId,
                tenantId,
                actorId,
                Instant.now(),
                actorId,
                Instant.now(),
                command.orgCode().trim(),
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
            throw new OptimisticLockingFailureException("organization node version is stale");
        }
        validateParent(tenantId, nodeId, before.path(), command.parentId());
        String newPath = pathFor(tenantId, command.parentId(), command.orgCode());
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
                   set org_code=?,org_name=?,org_type=?,parent_id=?,path=cast(? as ltree),
                       manager_employee_id=?,status=?,description=?,headcount_plan=?,sort_no=?,
                       updated_by=?,updated_at=now(),version_no=version_no+1
                 where tenant_id=? and id=? and not is_deleted and version_no=?
                """,
                command.orgCode().trim(),
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
        if (updated != 1) throw new OptimisticLockingFailureException("organization node version is stale");
        return requiredNode(tenantId, nodeId);
    }

    @Override
    public NodeView toggleNode(UUID tenantId, UUID actorId, UUID nodeId, ToggleCommand command) {
        requiredNode(tenantId, nodeId);
        String status = command.active() ? "ACTIVE" : "INACTIVE";
        if (command.cascade()) {
            jdbc.update(
                    """
                    with recursive subtree as (
                        select id from org.organization where tenant_id=? and id=? and not is_deleted
                        union all
                        select child.id from org.organization child
                        join subtree parent on child.parent_id=parent.id
                        where child.tenant_id=? and not child.is_deleted)
                    update org.organization o
                       set status=?,updated_by=?,updated_at=now(),version_no=version_no+1
                      from subtree s
                     where o.tenant_id=? and o.id=s.id
                    """,
                    tenantId,
                    nodeId,
                    tenantId,
                    status,
                    actorId,
                    tenantId);
        } else {
            jdbc.update(
                    """
                    update org.organization
                       set status=?,updated_by=?,updated_at=now(),version_no=version_no+1
                     where tenant_id=? and id=? and not is_deleted
                    """,
                    status,
                    actorId,
                    tenantId,
                    nodeId);
        }
        return requiredNode(tenantId, nodeId);
    }

    @Override
    public NodeView deleteNode(UUID tenantId, UUID actorId, UUID nodeId) {
        NodeView before = requiredNode(tenantId, nodeId);
        Integer children = jdbc.queryForObject(
                "select count(*) from org.organization where tenant_id=? and parent_id=? and not is_deleted",
                Integer.class,
                tenantId,
                nodeId);
        if (children != null && children > 0) {
            throw new IllegalArgumentException("organization with child nodes cannot be deleted");
        }
        Integer members = jdbc.queryForObject(
                """
                select count(*) from org.employee_position
                where tenant_id=? and org_id=? and not is_deleted and status='ACTIVE'
                  and effective_start_date<=current_date
                  and (effective_end_date is null or effective_end_date>=current_date)
                """,
                Integer.class,
                tenantId,
                nodeId);
        if (members != null && members > 0) {
            throw new IllegalArgumentException("organization with active members cannot be deleted");
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
                nodeId);
        return new NodeView(
                before.id(),
                before.orgCode(),
                before.orgName(),
                before.orgType(),
                before.parentId(),
                before.path(),
                "INACTIVE",
                before.managerEmployeeId(),
                before.memberCount(),
                before.headcountPlan(),
                before.sortNo(),
                before.versionNo() + 1,
                before.description());
    }

    @Override
    public long currentVersion(UUID tenantId) {
        Long value = jdbc.queryForObject(
                "select coalesce(max(version_no),0) from org.architecture_version where tenant_id=?",
                Long.class,
                tenantId);
        return value == null ? 0 : value;
    }

    @Override
    public long appendVersion(
            UUID tenantId, UUID actorId, UUID sourceDraftId, List<NodeView> snapshot, List<ChangeLine> changes) {
        long version = currentVersion(tenantId) + 1;
        jdbc.update(
                """
                insert into org.architecture_version(
                    tenant_id,version_no,source_draft_id,snapshot,change_summary,published_by,published_at)
                values (?,?,?,cast(? as jsonb),cast(? as jsonb),?,now())
                """,
                tenantId,
                version,
                sourceDraftId,
                json(snapshot),
                json(changes),
                actorId);
        return version;
    }

    @Override
    public Optional<DraftView> draft(UUID tenantId, UUID draftId) {
        return jdbc
                .query(
                        """
                        select * from org.architecture_draft
                        where tenant_id=? and id=? and not is_deleted
                        """,
                        (rs, rowNum) -> draft(rs),
                        tenantId,
                        draftId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<DraftView> latestEditableDraft(UUID tenantId, UUID actorId) {
        return jdbc
                .query(
                        """
                        select * from org.architecture_draft
                        where tenant_id=? and created_by=? and status in ('DRAFT','REJECTED') and not is_deleted
                        order by updated_at desc,id desc limit 1
                        """,
                        (rs, rowNum) -> draft(rs),
                        tenantId,
                        actorId)
                .stream()
                .findFirst();
    }

    @Override
    public List<DraftView> pendingDrafts(UUID tenantId) {
        return jdbc.query(
                """
                select * from org.architecture_draft
                where tenant_id=? and status='PENDING' and not is_deleted
                order by submitted_at,created_at,id
                """,
                (rs, rowNum) -> draft(rs),
                tenantId);
    }

    @Override
    public DraftView createDraft(
            UUID tenantId,
            UUID actorId,
            String title,
            long baseVersion,
            List<NodeView> snapshot,
            List<ChangeLine> changes) {
        UUID draftId = UUID.randomUUID();
        jdbc.update(
                """
                insert into org.architecture_draft(
                    id,tenant_id,created_by,updated_by,title,status,base_version,version_no,snapshot,change_summary)
                values (?,?,?,?,?,'DRAFT',?,0,cast(? as jsonb),cast(? as jsonb))
                """,
                draftId,
                tenantId,
                actorId,
                actorId,
                title,
                baseVersion,
                json(snapshot),
                json(changes));
        return requiredDraft(tenantId, draftId);
    }

    @Override
    public DraftView updateDraft(
            UUID tenantId,
            UUID actorId,
            UUID draftId,
            long expectedVersion,
            String title,
            List<NodeView> snapshot,
            List<ChangeLine> changes) {
        int updated = jdbc.update(
                """
                update org.architecture_draft
                   set title=?,snapshot=cast(? as jsonb),change_summary=cast(? as jsonb),
                       status='DRAFT',reviewed_by=null,reviewed_at=null,review_comment=null,
                       updated_by=?,updated_at=now(),version_no=version_no+1
                 where tenant_id=? and id=? and not is_deleted and version_no=?
                   and status in ('DRAFT','REJECTED')
                """,
                title,
                json(snapshot),
                json(changes),
                actorId,
                tenantId,
                draftId,
                expectedVersion);
        requireSingleDraftUpdate(updated);
        return requiredDraft(tenantId, draftId);
    }

    @Override
    public DraftView setDraftState(
            UUID tenantId,
            UUID actorId,
            UUID draftId,
            long expectedVersion,
            DraftStatus expectedStatus,
            DraftStatus targetStatus,
            String comment,
            String publishKey) {
        int updated =
                switch (targetStatus) {
                    case PENDING -> jdbc.update(
                            """
                    update org.architecture_draft
                       set status='PENDING',submitted_by=?,submitted_at=now(),updated_by=?,updated_at=now(),
                           version_no=version_no+1
                     where tenant_id=? and id=? and not is_deleted and status=? and version_no=?
                    """,
                            actorId,
                            actorId,
                            tenantId,
                            draftId,
                            expectedStatus.name(),
                            expectedVersion);
                    case APPROVED, REJECTED -> jdbc.update(
                            """
                    update org.architecture_draft
                       set status=?,reviewed_by=?,reviewed_at=now(),review_comment=?,updated_by=?,updated_at=now(),
                           version_no=version_no+1
                     where tenant_id=? and id=? and not is_deleted and status=? and version_no=?
                    """,
                            targetStatus.name(),
                            actorId,
                            trimToNull(comment),
                            actorId,
                            tenantId,
                            draftId,
                            expectedStatus.name(),
                            expectedVersion);
                    case PUBLISHED -> jdbc.update(
                            """
                    update org.architecture_draft
                       set status='PUBLISHED',published_by=?,published_at=now(),publish_key=?,
                           updated_by=?,updated_at=now(),version_no=version_no+1
                     where tenant_id=? and id=? and not is_deleted and status=? and version_no=?
                    """,
                            actorId,
                            publishKey,
                            actorId,
                            tenantId,
                            draftId,
                            expectedStatus.name(),
                            expectedVersion);
                    case ABANDONED -> jdbc.update(
                            """
                    update org.architecture_draft
                       set status='ABANDONED',abandoned_by=?,abandoned_at=now(),updated_by=?,updated_at=now(),
                           version_no=version_no+1
                     where tenant_id=? and id=? and not is_deleted and status=? and version_no=?
                    """,
                            actorId,
                            actorId,
                            tenantId,
                            draftId,
                            expectedStatus.name(),
                            expectedVersion);
                    case DRAFT -> throw new IllegalArgumentException("DRAFT is not a transition target");
                };
        requireSingleDraftUpdate(updated);
        return requiredDraft(tenantId, draftId);
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
                throw new IllegalArgumentException(
                        "organization with active members cannot be removed from a published snapshot: "
                                + existing.orgName());
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
            int updated = jdbc.update(
                    """
                    update org.organization
                       set org_code=?,org_name=?,org_type=?,parent_id=?,path=cast(? as ltree),status=?,
                           manager_employee_id=?,description=?,headcount_plan=?,sort_no=?,updated_by=?,updated_at=now(),
                           is_deleted=false,deleted_at=null,version_no=version_no+1
                     where tenant_id=? and id=?
                    """,
                    node.orgCode(),
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
                jdbc.update(
                        """
                        insert into org.organization(
                            id,tenant_id,created_by,created_at,updated_by,updated_at,is_deleted,
                            org_code,org_name,org_type,parent_id,path,manager_employee_id,status,
                            description,headcount_plan,sort_no,version_no)
                        values (?,?,?,?,?,?,false,?,?,?,?,cast(? as ltree),?,?,?,?,?,0)
                        """,
                        node.id(),
                        tenantId,
                        actorId,
                        Instant.now(),
                        actorId,
                        Instant.now(),
                        node.orgCode(),
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
        }
    }

    @Override
    public DirectoryView directory(UUID tenantId) {
        List<NodeView> organizations = tree(tenantId);
        List<DirectoryMember> members = jdbc.query(
                """
                select e.id employee_id,e.employee_no,e.person_name,ep.org_id,o.org_name,
                       ep.position_id,p.position_name
                from org.employee_position ep
                join org.employee e on e.tenant_id=ep.tenant_id and e.id=ep.employee_id and not e.is_deleted
                join org.organization o on o.tenant_id=ep.tenant_id and o.id=ep.org_id and not o.is_deleted
                join org.position p on p.tenant_id=ep.tenant_id and p.id=ep.position_id and not p.is_deleted
                where ep.tenant_id=? and not ep.is_deleted and ep.status='ACTIVE'
                  and ep.effective_start_date<=current_date
                  and (ep.effective_end_date is null or ep.effective_end_date>=current_date)
                order by o.path,o.sort_no,e.person_name,e.employee_no
                """,
                (rs, rowNum) -> new DirectoryMember(
                        rs.getObject("employee_id", UUID.class),
                        rs.getString("employee_no"),
                        rs.getString("person_name"),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("org_name"),
                        rs.getObject("position_id", UUID.class),
                        rs.getString("position_name")),
                tenantId);
        List<VersionStamp> stamps = jdbc.query(
                """
                select version_no,published_at from org.architecture_version
                where tenant_id=? order by version_no desc limit 1
                """,
                (rs, rowNum) -> new VersionStamp(rs.getLong("version_no"), instant(rs, "published_at")),
                tenantId);
        VersionStamp stamp = stamps.isEmpty() ? new VersionStamp(0, null) : stamps.getFirst();
        return new DirectoryView(stamp.versionNo(), stamp.publishedAt(), organizations, members);
    }

    @Override
    public Optional<NodeView> nodeCommandResult(UUID tenantId, String actionCode, String idempotencyKey) {
        return commandJson(tenantId, actionCode, idempotencyKey).map(value -> read(value, NodeView.class));
    }

    @Override
    public Optional<DraftView> draftCommandResult(UUID tenantId, String actionCode, String idempotencyKey) {
        return commandJson(tenantId, actionCode, idempotencyKey).map(value -> read(value, DraftView.class));
    }

    @Override
    public void recordNodeCommand(
            UUID tenantId,
            UUID actorId,
            UUID resourceId,
            String actionCode,
            String idempotencyKey,
            NodeView before,
            NodeView after) {
        recordCommand(tenantId, actorId, null, resourceId, actionCode, idempotencyKey, before, after, after);
    }

    @Override
    public void recordDraftCommand(
            UUID tenantId,
            UUID actorId,
            UUID draftId,
            String actionCode,
            String idempotencyKey,
            DraftView before,
            DraftView after) {
        recordCommand(tenantId, actorId, draftId, draftId, actionCode, idempotencyKey, before, after, after);
    }

    private void recordCommand(
            UUID tenantId,
            UUID actorId,
            UUID draftId,
            UUID resourceId,
            String actionCode,
            String idempotencyKey,
            Object before,
            Object after,
            Object response) {
        jdbc.update(
                """
                insert into org.architecture_change(
                    tenant_id,created_by,draft_id,resource_id,action_code,idempotency_key,
                    before_json,after_json,response_json)
                values (?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),cast(? as jsonb))
                """,
                tenantId,
                actorId,
                draftId,
                resourceId,
                actionCode,
                idempotencyKey,
                jsonNullable(before),
                jsonNullable(after),
                jsonNullable(response));
    }

    private Optional<String> commandJson(UUID tenantId, String actionCode, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        return jdbc
                .query(
                        """
                        select response_json::text from org.architecture_change
                        where tenant_id=? and action_code=? and idempotency_key=? and not is_deleted
                        order by created_at desc limit 1
                        """,
                        (rs, rowNum) -> rs.getString(1),
                        tenantId,
                        actionCode,
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    private NodeView requiredNode(UUID tenantId, UUID nodeId) {
        return node(tenantId, nodeId).orElseThrow(() -> new IllegalArgumentException("organization node not found"));
    }

    private DraftView requiredDraft(UUID tenantId, UUID draftId) {
        return draft(tenantId, draftId).orElseThrow(() -> new IllegalArgumentException("architecture draft not found"));
    }

    private String pathFor(UUID tenantId, UUID parentId, String orgCode) {
        String label = pathLabel(orgCode);
        if (parentId == null) return label;
        NodeView parent = requiredNode(tenantId, parentId);
        return parent.path() + "." + label;
    }

    private void validateParent(UUID tenantId, UUID nodeId, String oldPath, UUID parentId) {
        if (parentId == null) return;
        if (nodeId.equals(parentId)) throw new IllegalArgumentException("organization cannot be its own parent");
        NodeView parent = requiredNode(tenantId, parentId);
        Boolean descendant = jdbc.queryForObject(
                "select cast(? as ltree) <@ cast(? as ltree)", Boolean.class, parent.path(), oldPath);
        if (Boolean.TRUE.equals(descendant)) {
            throw new IllegalArgumentException("organization cannot move below its own descendant");
        }
    }

    private DraftView draft(ResultSet rs) throws SQLException {
        return new DraftView(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                DraftStatus.valueOf(rs.getString("status")),
                rs.getLong("base_version"),
                rs.getLong("version_no"),
                read(rs.getString("snapshot"), NODE_LIST),
                read(rs.getString("change_summary"), CHANGE_LIST),
                rs.getObject("created_by", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getObject("submitted_by", UUID.class),
                instant(rs, "submitted_at"),
                rs.getObject("reviewed_by", UUID.class),
                instant(rs, "reviewed_at"),
                rs.getString("review_comment"),
                rs.getObject("published_by", UUID.class),
                instant(rs, "published_at"),
                rs.getString("publish_key"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static NodeView node(ResultSet rs) throws SQLException {
        return new NodeView(
                rs.getObject("id", UUID.class),
                rs.getString("org_code"),
                rs.getString("org_name"),
                rs.getString("org_type"),
                rs.getObject("parent_id", UUID.class),
                rs.getString("path"),
                rs.getString("status"),
                rs.getObject("manager_employee_id", UUID.class),
                rs.getInt("member_count"),
                rs.getInt("headcount_plan"),
                rs.getInt("sort_no"),
                rs.getLong("version_no"),
                rs.getString("description"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("organization architecture JSON serialization failed", exception);
        }
    }

    private String jsonNullable(Object value) {
        return value == null ? null : json(value);
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("organization architecture JSON contract is invalid", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("organization architecture JSON contract is invalid", exception);
        }
    }

    private static void requireSingleDraftUpdate(int updated) {
        if (updated != 1) throw new OptimisticLockingFailureException("architecture draft version or state is stale");
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

    private record VersionStamp(long versionNo, Instant publishedAt) {}
}
