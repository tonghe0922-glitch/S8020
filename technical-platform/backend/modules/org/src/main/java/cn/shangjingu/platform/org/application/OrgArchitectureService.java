package cn.shangjingu.platform.org.application;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import java.util.List;
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
        OrgArchitectureValidator.validate(command);
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.nodeCommandResult(actor.tenantId(), "ORG_NODE_CREATE", key);
            if (replay.isPresent()) return new Mutation<>(null, replay.get());
            NodeView after = repository.createNode(actor.tenantId(), actor.userId(), command);
            appendDirectVersion(actor, "ADDED", after, "新增组织：" + after.orgName());
            repository.recordNodeCommand(
                    actor.tenantId(), actor.userId(), after.id(), "ORG_NODE_CREATE", key, null, after);
            return new Mutation<>(null, after);
        });
    }

    public Mutation<NodeView> updateNode(
            DatabaseSecurityContext actor, UUID nodeId, String idempotencyKey, NodeCommand command) {
        OrgArchitectureValidator.validate(command);
        String key = requireKey(idempotencyKey);
        return transactions.required(actor, () -> {
            var replay = repository.nodeCommandResult(actor.tenantId(), "ORG_NODE_UPDATE", key);
            if (replay.isPresent()) return new Mutation<>(replay.get(), replay.get());
            NodeView before = requiredNode(actor.tenantId(), nodeId);
            NodeView after = repository.updateNode(actor.tenantId(), actor.userId(), nodeId, command);
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
            NodeView after = repository.toggleNode(actor.tenantId(), actor.userId(), nodeId, command);
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
            NodeView after = repository.deleteNode(actor.tenantId(), actor.userId(), nodeId);
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
                .orElseThrow(() -> new IllegalArgumentException("editable architecture draft not found")));
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
        List<NodeView> normalized = OrgArchitectureValidator.normalizeSnapshot(command.snapshot());
        return transactions.required(actor, () -> {
            var replay = repository.draftCommandResult(actor.tenantId(), "ORG_DRAFT_UPDATE", key);
            if (replay.isPresent()) return replay.get();
            DraftView before = requiredDraft(actor.tenantId(), draftId);
            requireEditableBy(actor, before);
            List<ChangeLine> changes =
                    OrgArchitectureDiffCalculator.calculate(repository.tree(actor.tenantId()), normalized);
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
            if (before.changes().isEmpty()) throw new IllegalArgumentException("architecture draft has no changes");
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
                throw new IllegalArgumentException("only a pending architecture draft can be reviewed");
            }
            if (Objects.equals(before.createdBy(), actor.userId())) {
                throw new IllegalArgumentException("architecture draft creator cannot review the same draft");
            }
            DraftStatus target = command.approved() ? DraftStatus.APPROVED : DraftStatus.REJECTED;
            if (!command.approved()
                    && (command.comment() == null || command.comment().isBlank())) {
                throw new IllegalArgumentException("rejection comment is required");
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
                throw new IllegalArgumentException("only an approved architecture draft can be published");
            }
            requireCurrentBase(actor.tenantId(), before);
            List<NodeView> normalized = OrgArchitectureValidator.normalizeSnapshot(before.snapshot());
            repository.applySnapshot(actor.tenantId(), actor.userId(), normalized);
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
                .orElseThrow(() -> new IllegalArgumentException("organization node not found"));
    }

    private DraftView requiredDraft(UUID tenantId, UUID draftId) {
        return repository
                .draft(tenantId, draftId)
                .orElseThrow(() -> new IllegalArgumentException("architecture draft not found"));
    }

    private static void requireEditableBy(DatabaseSecurityContext actor, DraftView draft) {
        if (!Objects.equals(actor.userId(), draft.createdBy())) {
            throw new IllegalArgumentException("only the architecture draft creator can edit or submit it");
        }
        if (draft.status() != DraftStatus.DRAFT && draft.status() != DraftStatus.REJECTED) {
            throw new IllegalArgumentException("architecture draft is not editable in its current state");
        }
    }

    private void requireCurrentBase(UUID tenantId, DraftView draft) {
        long current = repository.currentVersion(tenantId);
        if (draft.baseVersion() != current) {
            throw new OptimisticLockingFailureException(
                    "published organization architecture changed after this draft was created");
        }
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 160 characters");
        }
        return value.trim();
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("draft title is invalid");
        }
        return value.trim();
    }
}
