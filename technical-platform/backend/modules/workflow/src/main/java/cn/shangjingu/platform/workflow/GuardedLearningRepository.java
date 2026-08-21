package cn.shangjingu.platform.workflow;

import cn.shangjingu.platform.core.process.ProcessRejectedException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * Production fail-closed adapter for P010 canonical mutations.
 *
 * <p>The application service deliberately depends on the repository interface. This primary
 * adapter guarantees that a guarded JDBC update which affects zero or multiple rows aborts the
 * surrounding tenant transaction instead of allowing the workflow projection to advance.</p>
 */
@Primary
@Repository
public class GuardedLearningRepository implements LearningService.Repository {
    private final JdbcLearningRepository delegate;
    private final P010LearningInsertWriter insertWriter;

    public GuardedLearningRepository(JdbcLearningRepository delegate) {
        this(delegate, null);
    }

    @Autowired
    public GuardedLearningRepository(
            JdbcLearningRepository delegate, P010LearningInsertWriter insertWriter) {
        this.delegate = delegate;
        this.insertWriter = insertWriter;
    }

    @Override
    public Optional<UUID> workflowVersion(UUID tenantId) {
        return delegate.workflowVersion(tenantId);
    }

    @Override
    public Optional<LearningService.FormRef> form(UUID tenantId) {
        return delegate.form(tenantId);
    }

    @Override
    public List<UUID> permissionCandidates(
            UUID tenantId, String permission, UUID orgId) {
        return delegate.permissionCandidates(tenantId, permission, orgId);
    }

    @Override
    public boolean activeEmployeeInCenter(
            UUID tenantId, UUID employeeId, UUID orgId) {
        return delegate.activeEmployeeInCenter(
                tenantId, employeeId, orgId);
    }

    @Override
    public void insert(
            LearningService.LearningRecord record,
            String reason,
            String courseTeamName,
            String riskLevel,
            String learnerProfile,
            Instant plannedStartAt,
            Instant plannedFinishAt,
            UUID actor) {
        if (insertWriter == null) {
            delegate.insert(
                    record,
                    reason,
                    courseTeamName,
                    riskLevel,
                    learnerProfile,
                    plannedStartAt,
                    plannedFinishAt,
                    actor);
            return;
        }
        insertWriter.insert(
                record,
                reason,
                courseTeamName,
                riskLevel,
                learnerProfile,
                plannedStartAt,
                plannedFinishAt,
                actor);
    }

    @Override
    public Optional<LearningService.LearningRecord> find(
            UUID tenantId, UUID id) {
        return delegate.find(tenantId, id);
    }

    @Override
    public List<LearningService.LearningRecord> list(UUID tenantId) {
        return delegate.list(tenantId);
    }

    @Override
    public List<LearningService.Evidence> evidence(
            UUID tenantId, UUID id) {
        return delegate.evidence(tenantId, id);
    }

    @Override
    public int bindWorkflow(
            UUID tenantId,
            UUID id,
            int version,
            UUID workflowId,
            String node,
            String status,
            UUID actor) {
        return required(
                delegate.bindWorkflow(
                        tenantId,
                        id,
                        version,
                        workflowId,
                        node,
                        status,
                        actor),
                "workflow binding");
    }

    @Override
    public int moveNode(
            UUID tenantId,
            UUID id,
            int version,
            String node,
            String status,
            Instant closed,
            UUID actor) {
        return required(
                delegate.moveNode(
                        tenantId,
                        id,
                        version,
                        node,
                        status,
                        closed,
                        actor),
                "workflow projection transition");
    }

    @Override
    public void appendEvidence(
            UUID tenantId,
            UUID id,
            String type,
            UUID actor,
            Long score,
            BigDecimal progress,
            String practical,
            String text,
            JsonNode json) {
        delegate.appendEvidence(
                tenantId,
                id,
                type,
                actor,
                score,
                progress,
                practical,
                text,
                json);
    }

    @Override
    public int updateProgress(
            UUID tenantId, UUID id, BigDecimal progress, UUID actor) {
        return required(
                delegate.updateProgress(
                        tenantId, id, progress, actor),
                "learning progress update");
    }

    @Override
    public int markLearningCompleted(
            UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markLearningCompleted(tenantId, id, actor),
                "learning completion fact");
    }

    @Override
    public int updateExam(
            UUID tenantId, UUID id, long score, UUID actor) {
        return required(
                delegate.updateExam(tenantId, id, score, actor),
                "exam result update");
    }

    @Override
    public int updatePractical(
            UUID tenantId, UUID id, String result, UUID actor) {
        return required(
                delegate.updatePractical(
                        tenantId, id, result, actor),
                "practical result update");
    }

    @Override
    public int markContentPublished(
            UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markContentPublished(tenantId, id, actor),
                "content publication fact");
    }

    @Override
    public int markRiskAssigned(
            UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markRiskAssigned(tenantId, id, actor),
                "risk assignment fact");
    }

    @Override
    public int markCertified(
            UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markCertified(tenantId, id, actor),
                "professional certification fact");
    }

    @Override
    public int activateQualification(
            UUID tenantId,
            UUID id,
            LocalDate effective,
            LocalDate expire,
            UUID actor) {
        return required(
                delegate.activateQualification(
                        tenantId, id, effective, expire, actor),
                "qualification activation fact");
    }

    @Override
    public List<UUID> linkPermissions(
            UUID tenantId, UUID id, UUID actor) {
        return delegate.linkPermissions(tenantId, id, actor);
    }

    @Override
    public int markPermissionLinked(
            UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markPermissionLinked(tenantId, id, actor),
                "permission linkage fact");
    }

    @Override
    public int markRetrainingChecked(
            UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markRetrainingChecked(tenantId, id, actor),
                "retraining check fact");
    }

    @Override
    public int markArchived(
            UUID tenantId, UUID id, UUID actor) {
        return required(
                delegate.markArchived(tenantId, id, actor),
                "archive fact");
    }

    private static int required(int updated, String operation) {
        if (updated != 1) {
            throw new ProcessRejectedException(
                    "P010 " + operation + " failed closed");
        }
        return updated;
    }
}
