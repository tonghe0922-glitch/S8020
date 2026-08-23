package cn.shangjingu.platform.org.application;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class OrgArchitectureService {
    private final TenantTransactionRunner transactions;
    private final OrgArchitectureRepository repository;

    public OrgArchitectureService(TenantTransactionRunner transactions, OrgArchitectureRepository repository) {
        this.transactions = transactions;
        this.repository = repository;
    }

    public List<NodeView> tree(DatabaseSecurityContext actor) {
        return transactions.required(actor, () -> repository.tree(actor.tenantId()));
    }

    public DirectoryView directory(DatabaseSecurityContext actor) {
        return transactions.required(actor, () -> repository.directory(actor.tenantId()));
    }

    public Mutation<NodeView> createNode(DatabaseSecurityContext actor, String idempotencyKey, NodeCommand command) {
        Objects.requireNonNull(command, "command");
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.nodeCommandResult(actor.tenantId(), "ORG_NODE_CREATE", key);
            if (replay.isPresent()) return new Mutation<>(null, replay.get());
            NodeCommand managed = withOrgCode(command, repository.allocateOrgCode(actor.tenantId()));
            OrgArchitectureValidator.validate(managed);
            NodeView after = repository.createNode(actor.tenantId(), requireEmployeeActor(actor), managed);
            appendDirectVersion(actor, "ADDED", after, "新增组织：" + after.orgName());
            repository.recordNodeCommand(
                    actor.tenantId(), actor.userId(), after.id(), "ORG_NODE_CREATE", key, null, after);
            return new Mutation<>(null, after);
        });
    }

    public Mutation<NodeView> updateNode(
            DatabaseSecurityContext actor, UUID nodeId, String idempotencyKey, NodeCommand command) {
        Objects.requireNonNull(command, "command");
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.nodeCommandResult(actor.tenantId(), "ORG_NODE_UPDATE", key);
            if (replay.isPresent()) return new Mutation<>(replay.get(), replay.get());
            NodeView before = requiredNode(actor.tenantId(), nodeId);
            NodeCommand managed = withOrgCode(command, before.orgCode());
            OrgArchitectureValidator.validate(managed);
            NodeView after = repository.updateNode(
                    actor.tenantId(), requireEmployeeActor(actor), nodeId, managed);
            appendDirectVersion(actor, "UPDATED", after, "调整组织：" + after.orgName());
            repository.recordNodeCommand(
                    actor.tenantId(), actor.userId(), nodeId, "ORG_NODE_UPDATE", key, before, after);
            return new Mutation<>(before, after);
        });
    }

    public Mutation<NodeView> toggleNode(
            DatabaseSecurityContext actor, UUID nodeId, String idempotencyKey, ToggleCommand command) {
        Objects.requireNonNull(command, "command");
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.nodeCommandResult(actor.tenantId(), "ORG_NODE_TOGGLE", key);
            if (replay.isPresent()) return new Mutation<>(replay.get(), replay.get());
            NodeView before = requiredNode(actor.tenantId(), nodeId);
            NodeView after = repository.toggleNode(actor.tenantId(), requireEmployeeActor(actor), nodeId, command);
            appendDirectVersion(actor, "UPDATED", after, "调整组织状态：" + after.orgName());
            repository.recordNodeCommand(
                    actor.tenantId(), actor.userId(), nodeId, "ORG_NODE_TOGGLE", key, before, after);
            return new Mutation<>(before, after);
        });
    }

    public Mutation<NodeView> deleteNode(DatabaseSecurityContext actor, UUID nodeId, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.nodeCommandResult(actor.tenantId(), "ORG_NODE_DELETE", key);
            if (replay.isPresent()) return new Mutation<>(replay.get(), replay.get());
            NodeView before = requiredNode(actor.tenantId(), nodeId);
            NodeView after = repository.deleteNode(actor.tenantId(), requireEmployeeActor(actor), nodeId);
            List<NodeView> snapshot = repository.tree(actor.tenantId());
            repository.appendVersion(
                    actor.tenantId(),
                    actor.userId(),
                    null,
                    snapshot,
                    List.of(new ChangeLine("REMOVED", before.id(), before.orgCode(), "停用组织：" + before.orgName())));
            repository.recordNodeCommand(
                    actor.tenantId(), actor.userId(), nodeId, "ORG_NODE_DELETE", key, before, after);
            return new Mutation<>(before, after);
        });
    }

    public DraftView draft(DatabaseSecurityContext actor, UUID draftId) {
        return transactions.required(actor, () -> requiredDraft(actor.tenantId(), draftId));
    }

    public DraftView latestEditableDraft(DatabaseSecurityContext actor) {
        return transactions.required(actor, () -> repository
                .latestEditableDraft(actor.tenantId(), actor.userId())
                .orElseThrow(() -> new IllegalArgumentException("未找到可编辑的组织架构草稿")));
    }

    public List<DraftView> pendingDrafts(DatabaseSecurityContext actor) {
        return transactions.required(actor, () -> repository.pendingDrafts(actor.tenantId()));
    }

    public DraftView createDraft(DatabaseSecurityContext actor, String idempotencyKey, DraftCreateCommand command) {
        Objects.requireNonNull(command, "command");
        String title = requireTitle(command.title());
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.draftCommandResult(actor.tenantId(), "ORG_DRAFT_CREATE", key);
            if (replay.isPresent()) return replay.get();
            var existing = repository.latestEditableDraft(actor.tenantId(), actor.userId());
            if (existing.isPresent()) {
                DraftView current = existing.get();
                repository.recordDraftCommand(
                        actor.tenantId(), actor.userId(), current.id(), "ORG_DRAFT_CREATE", key, current, current);
                return current;
            }
            List<NodeView> snapshot = repository.tree(actor.tenantId());
            DraftView after = repository.createDraft(
                    actor.tenantId(),
                    actor.userId(),
                    title,
                    repository.currentVersion(actor.tenantId()),
                    snapshot,
                    List.of());
            repository.recordDraftCommand(
                    actor.tenantId(), actor.userId(), after.id(), "ORG_DRAFT_CREATE", key, null, after);
            return after;
        });
    }

    public DraftView updateDraft(
            DatabaseSecurityContext actor, UUID draftId, String idempotencyKey, DraftUpdateCommand command) {
        Objects.requireNonNull(command, "command");
        String key = requireKey(idempotencyKey);
        String title = requireTitle(command.title());
        return transactions.required(actor, () -> {
            var replay = repository.draftCommandResult(actor.tenantId(), "ORG_DRAFT_UPDATE", key);
            if (replay.isPresent()) return replay.get();
            DraftView before = requiredDraft(actor.tenantId(), draftId);
            requireEditableBy(actor, before);
            List<NodeView> published = repository.tree(actor.tenantId());
            List<NodeView> assigned = assignSnapshotCodes(actor.tenantId(), published, before.snapshot(), command.snapshot());
            List<NodeView> normalized = OrgArchitectureValidator.normalizeSnapshot(assigned);
            List<ChangeLine> changes = OrgArchitectureDiffCalculator.calculate(published, normalized);
            DraftView after = repository.updateDraft(
                    actor.tenantId(), actor.userId(), draftId, command.expectedVersion(), title, normalized, changes);
            repository.recordDraftCommand(
                    actor.tenantId(), actor.userId(), draftId, "ORG_DRAFT_UPDATE", key, before, after);
            return after;
        });
    }

    public DraftView submitDraft(
            DatabaseSecurityContext actor, UUID draftId, String idempotencyKey, DraftActionCommand command) {
        Objects.requireNonNull(command, "command");
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.draftCommandResult(actor.tenantId(), "ORG_DRAFT_SUBMIT", key);
            if (replay.isPresent()) return replay.get();
            DraftView before = requiredDraft(actor.tenantId(), draftId);
            requireEditableBy(actor, before);
            if (before.changes().isEmpty()) throw new IllegalArgumentException("组织架构草稿没有可提交的变更");
            requireCurrentBase(actor.tenantId(), before);
            DraftView after = repository.setDraftState(
                    actor.tenantId(),
                    actor.userId(),
                    draftId,
                    command.expectedVersion(),
                    before.status(),
                    DraftStatus.PENDING,
                    null,
                    null);
            repository.recordDraftCommand(
                    actor.tenantId(), actor.userId(), draftId, "ORG_DRAFT_SUBMIT", key, before, after);
            return after;
        });
    }

    public DraftView reviewDraft(
            DatabaseSecurityContext actor, UUID draftId, String idempotencyKey, ReviewCommand command) {
        Objects.requireNonNull(command, "command");
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.draftCommandResult(actor.tenantId(), "ORG_DRAFT_REVIEW", key);
            if (replay.isPresent()) return replay.get();
            DraftView before = requiredDraft(actor.tenantId(), draftId);
            if (before.status() != DraftStatus.PENDING) {
                throw new IllegalArgumentException("仅待审批的组织架构草稿可以审核");
            }
            if (Objects.equals(before.createdBy(), actor.userId())) {
                throw new IllegalArgumentException("组织架构草稿创建人不能审核自己的草稿");
            }
            DraftStatus target = command.approved() ? DraftStatus.APPROVED : DraftStatus.REJECTED;
            if (!command.approved()
                    && (command.comment() == null || command.comment().isBlank())) {
                throw new IllegalArgumentException("驳回时必须填写原因");
            }
            DraftView after = repository.setDraftState(
                    actor.tenantId(),
                    actor.userId(),
                    draftId,
                    command.expectedVersion(),
                    DraftStatus.PENDING,
                    target,
                    command.comment(),
                    null);
            repository.recordDraftCommand(
                    actor.tenantId(), actor.userId(), draftId, "ORG_DRAFT_REVIEW", key, before, after);
            return after;
        });
    }

    public DraftView publishDraft(
            DatabaseSecurityContext actor, UUID draftId, String idempotencyKey, DraftActionCommand command) {
        Objects.requireNonNull(command, "command");
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.draftCommandResult(actor.tenantId(), "ORG_DRAFT_PUBLISH", key);
            if (replay.isPresent()) return replay.get();
            DraftView before = requiredDraft(actor.tenantId(), draftId);
            if (before.status() != DraftStatus.APPROVED) {
                throw new IllegalArgumentException("仅已审批通过的组织架构草稿可以发布");
            }
            requireCurrentBase(actor.tenantId(), before);
            List<NodeView> normalized = OrgArchitectureValidator.normalizeSnapshot(before.snapshot());
            repository.applySnapshot(actor.tenantId(), requireEmployeeActor(actor), normalized);
            List<NodeView> published = repository.tree(actor.tenantId());
            repository.appendVersion(actor.tenantId(), actor.userId(), draftId, published, before.changes());
            DraftView after = repository.setDraftState(
                    actor.tenantId(),
                    actor.userId(),
                    draftId,
                    command.expectedVersion(),
                    DraftStatus.APPROVED,
                    DraftStatus.PUBLISHED,
                    null,
                    key);
            repository.recordDraftCommand(
                    actor.tenantId(), actor.userId(), draftId, "ORG_DRAFT_PUBLISH", key, before, after);
            return after;
        });
    }

    public DraftView abandonDraft(
            DatabaseSecurityContext actor, UUID draftId, String idempotencyKey, DraftActionCommand command) {
        Objects.requireNonNull(command, "command");
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.draftCommandResult(actor.tenantId(), "ORG_DRAFT_ABANDON", key);
            if (replay.isPresent()) return replay.get();
            DraftView before = requiredDraft(actor.tenantId(), draftId);
            requireEditableBy(actor, before);
            DraftView after = repository.setDraftState(
                    actor.tenantId(),
                    actor.userId(),
                    draftId,
                    command.expectedVersion(),
                    before.status(),
                    DraftStatus.ABANDONED,
                    null,
                    null);
            repository.recordDraftCommand(
                    actor.tenantId(), actor.userId(), draftId, "ORG_DRAFT_ABANDON", key, before, after);
            return after;
        });
    }

    private void appendDirectVersion(DatabaseSecurityContext actor, String kind, NodeView node, String summary) {
        repository.appendVersion(
                actor.tenantId(),
                actor.userId(),
                null,
                repository.tree(actor.tenantId()),
                List.of(new ChangeLine(kind, node.id(), node.orgCode(), summary)));
    }

    private NodeView requiredNode(UUID tenantId, UUID nodeId) {
        return repository
                .node(tenantId, nodeId)
                .orElseThrow(() -> new IllegalArgumentException("组织节点不存在"));
    }

    private DraftView requiredDraft(UUID tenantId, UUID draftId) {
        return repository
                .draft(tenantId, draftId)
                .orElseThrow(() -> new IllegalArgumentException("组织架构草稿不存在"));
    }

    private static void requireEditableBy(DatabaseSecurityContext actor, DraftView draft) {
        if (!Objects.equals(actor.userId(), draft.createdBy())) {
            throw new IllegalArgumentException("仅组织架构草稿创建人可以编辑或提交该草稿");
        }
        if (draft.status() != DraftStatus.DRAFT && draft.status() != DraftStatus.REJECTED) {
            throw new IllegalArgumentException("组织架构草稿当前状态不允许编辑");
        }
    }

    private void requireCurrentBase(UUID tenantId, DraftView draft) {
        long current = repository.currentVersion(tenantId);
        if (draft.baseVersion() != current) {
            throw new OptimisticLockingFailureException(
                    "正式组织架构已在草稿创建后发生变化，请刷新后重新创建草稿");
        }
    }

    private List<NodeView> assignSnapshotCodes(
            UUID tenantId, List<NodeView> published, List<NodeView> previousDraft, List<NodeView> submitted) {
        List<NodeView> safe = submitted == null ? List.of() : List.copyOf(submitted);
        Map<UUID, String> canonicalCodes = new HashMap<>();
        published.forEach(node -> canonicalCodes.put(node.id(), node.orgCode()));
        List<NodeView> safePrevious = previousDraft == null ? List.of() : previousDraft;
        safePrevious.forEach(node -> canonicalCodes.putIfAbsent(node.id(), node.orgCode()));
        List<NodeView> assigned = new ArrayList<>(safe.size());
        for (NodeView node : safe) {
            Objects.requireNonNull(node, "snapshot node");
            String code = canonicalCodes.get(node.id());
            if (code == null || code.isBlank()) {
                code = repository.allocateOrgCode(tenantId);
                canonicalCodes.put(node.id(), code);
            }
            assigned.add(withOrgCode(node, code));
        }
        return List.copyOf(assigned);
    }

    private static NodeCommand withOrgCode(NodeCommand command, String orgCode) {
        return new NodeCommand(
                orgCode,
                command.orgName(),
                command.orgType(),
                command.parentId(),
                command.status(),
                command.managerEmployeeId(),
                command.headcountPlan(),
                command.sortNo(),
                command.expectedVersion(),
                command.description());
    }

    private static NodeView withOrgCode(NodeView node, String orgCode) {
        return new NodeView(
                node.id(),
                orgCode,
                node.orgName(),
                node.orgType(),
                node.parentId(),
                node.path(),
                node.status(),
                node.managerEmployeeId(),
                node.memberCount(),
                node.headcountPlan(),
                node.sortNo(),
                node.versionNo(),
                node.description());
    }

    private static UUID requireEmployeeActor(DatabaseSecurityContext actor) {
        if (actor.employeeId() == null) {
            throw new IllegalArgumentException("当前登录身份未关联有效员工档案，无法维护组织架构");
        }
        return actor.employeeId();
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("幂等键不能为空且长度不能超过 160 个字符");
        }
        return value.trim();
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("草稿标题不能为空且长度不能超过 160 个字符");
        }
        return value.trim();
    }
}
