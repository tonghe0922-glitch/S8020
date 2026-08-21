package cn.shangjingu.platform.workflow.phase11;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** P011 application service. Employee, supervisor, authoritative and calibrated scores stay independent. */
@Service
public final class PerformanceService {
    private static final Phase11Process PROCESS = Phase11Process.P011;
    private final Phase11LifecycleService lifecycle;

    public PerformanceService(Phase11LifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    public Phase11Record create(
            DatabaseSecurityContext actor,
            String idempotencyKey,
            String requestHash,
            CreateCommand command) {
        return lifecycle.create(
                actor,
                PROCESS,
                idempotencyKey,
                requestHash,
                new Phase11CreateData(
                        command.subject(),
                        command.reason(),
                        command.priority(),
                        command.riskLevel(),
                        command.ownerCenterId(),
                        command.ownerEmployeeId(),
                        command.businessDate(),
                        command.factOccurredAt(),
                        command.goalSummary(),
                        command.contentVersion(),
                        command.periodNo()));
    }

    public Phase11Record act(
            DatabaseSecurityContext actor,
            UUID cycleId,
            String actionCode,
            String idempotencyKey,
            String requestHash,
            ActionCommand command) {
        return lifecycle.act(
                actor,
                PROCESS,
                cycleId,
                actionCode,
                idempotencyKey,
                requestHash,
                command.toInternal());
    }

    public Phase11Record submitScore(
            DatabaseSecurityContext actor,
            UUID cycleId,
            String scoreType,
            String idempotencyKey,
            String requestHash,
            ScoreCommand command) {
        return lifecycle.submitPerformanceScore(
                actor,
                cycleId,
                scoreType,
                command.score1000(),
                command.evidenceSummary(),
                command.expectedVersion(),
                idempotencyKey,
                requestHash);
    }

    public Optional<Phase11Record> find(
            DatabaseSecurityContext actor, UUID cycleId) {
        return lifecycle.find(actor, PROCESS, cycleId);
    }

    public List<Phase11Record> list(DatabaseSecurityContext actor) {
        return lifecycle.list(actor, PROCESS);
    }

    public record CreateCommand(
            String subject,
            String reason,
            String priority,
            String riskLevel,
            UUID ownerCenterId,
            UUID ownerEmployeeId,
            LocalDate businessDate,
            Instant factOccurredAt,
            String goalSummary,
            String contentVersion,
            String periodNo) {}

    public record ScoreCommand(
            int expectedVersion, long score1000, String evidenceSummary) {}

    public record ActionCommand(
            int expectedVersion,
            String summary,
            String reason,
            Boolean appealRequested,
            String appealReason,
            String decision) {
        Phase11ActionData toInternal() {
            return new Phase11ActionData(
                    expectedVersion,
                    summary,
                    reason,
                    null,
                    appealRequested,
                    appealReason,
                    decision);
        }
    }
}
