package cn.shangjingu.platform.org.application;

import static cn.shangjingu.platform.org.domain.OrgArchitectureRecords.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgArchitectureRepository {
    List<NodeView> tree(UUID tenantId);

    Optional<NodeView> node(UUID tenantId, UUID nodeId);

    String allocateOrgCode(UUID tenantId);

    NodeView createNode(UUID tenantId, UUID actorId, NodeCommand command);

    NodeView updateNode(UUID tenantId, UUID actorId, UUID nodeId, NodeCommand command);

    NodeView toggleNode(UUID tenantId, UUID actorId, UUID nodeId, ToggleCommand command);

    NodeView deleteNode(UUID tenantId, UUID actorId, UUID nodeId);

    long currentVersion(UUID tenantId);

    long appendVersion(
            UUID tenantId, UUID actorId, UUID sourceDraftId, List<NodeView> snapshot, List<ChangeLine> changes);

    Optional<DraftView> draft(UUID tenantId, UUID draftId);

    Optional<DraftView> latestEditableDraft(UUID tenantId, UUID actorId);

    List<DraftView> pendingDrafts(UUID tenantId);

    DraftView createDraft(
            UUID tenantId,
            UUID actorId,
            String title,
            long baseVersion,
            List<NodeView> snapshot,
            List<ChangeLine> changes);

    DraftView updateDraft(
            UUID tenantId,
            UUID actorId,
            UUID draftId,
            long expectedVersion,
            String title,
            List<NodeView> snapshot,
            List<ChangeLine> changes);

    DraftView setDraftState(
            UUID tenantId,
            UUID actorId,
            UUID draftId,
            long expectedVersion,
            DraftStatus expectedStatus,
            DraftStatus targetStatus,
            String comment,
            String publishKey);

    void applySnapshot(UUID tenantId, UUID actorId, List<NodeView> snapshot);

    DirectoryView directory(UUID tenantId);

    Optional<NodeView> nodeCommandResult(UUID tenantId, String actionCode, String idempotencyKey);

    Optional<DraftView> draftCommandResult(UUID tenantId, String actionCode, String idempotencyKey);

    void recordNodeCommand(
            UUID tenantId,
            UUID actorId,
            UUID resourceId,
            String actionCode,
            String idempotencyKey,
            NodeView before,
            NodeView after);

    void recordDraftCommand(
            UUID tenantId,
            UUID actorId,
            UUID draftId,
            String actionCode,
            String idempotencyKey,
            DraftView before,
            DraftView after);
}
