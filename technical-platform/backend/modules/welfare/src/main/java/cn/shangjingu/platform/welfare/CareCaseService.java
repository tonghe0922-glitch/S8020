package cn.shangjingu.platform.welfare;

import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.core.database.TenantTransactionRunner;
import cn.shangjingu.platform.core.process.BusinessNumberService;
import cn.shangjingu.platform.core.process.IdempotencyClaim;
import cn.shangjingu.platform.core.process.IdempotencyRegistry;
import cn.shangjingu.platform.core.process.ProcessRejectedException;
import cn.shangjingu.platform.core.process.SequentialStateMachine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CareCaseService {
    public static final String PROCESS_CODE = "P016";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final SequentialStateMachine STATES =
            new SequentialStateMachine(List.of("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08"));

    private final TenantTransactionRunner transactions;
    private final IdempotencyRegistry idempotency;
    private final BusinessNumberService numbers;
    private final Repository repository;
    private final List<FinanceCapability> financeCapabilities;

    public CareCaseService(
            TenantTransactionRunner transactions,
            IdempotencyRegistry idempotency,
            BusinessNumberService numbers,
            Repository repository,
            List<FinanceCapability> financeCapabilities) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.repository = repository;
        this.financeCapabilities = List.copyOf(financeCapabilities);
    }

    public CareCase create(
            DatabaseSecurityContext actor, String idempotencyKey, String requestHash, CreateCommand command) {
        Objects.requireNonNull(actor, "actor");
        validate(command);
        return transactions.required(actor, () -> {
            UUID proposedId = UUID.randomUUID();
            IdempotencyClaim claim = idempotency.claim(
                    actor.tenantId(),
                    actor.userId(),
                    idempotencyKey,
                    requestHash,
                    "welfare.care_case",
                    proposedId,
                    IDEMPOTENCY_TTL);
            if (claim.existing()) {
                return repository
                        .find(actor.tenantId(), claim.resourceId())
                        .orElseThrow(() -> new ProcessRejectedException("idempotent care case no longer exists"));
            }
            String businessNo = numbers.next(actor.tenantId(), actor.userId(), PROCESS_CODE);
            CareCase created = new CareCase(
                    proposedId,
                    actor.tenantId(),
                    businessNo,
                    STATES.initial(),
                    0,
                    command.sourceChannel(),
                    command.businessDate(),
                    command.subject(),
                    command.reason(),
                    command.priority(),
                    command.riskLevel(),
                    command.ownerCenterId(),
                    command.ownerDepartmentId(),
                    command.ownerEmployeeId(),
                    command.benefitAmount(),
                    command.budgetItemId(),
                    command.costCenterId(),
                    command.currency(),
                    command.employeeEventType(),
                    command.factOccurredAt(),
                    command.factSummary(),
                    command.impactEffectiveDate(),
                    command.impactLevel(),
                    command.pointsDelta(),
                    null,
                    null,
                    null,
                    null);
            repository.insert(created, actor.userId());
            return created;
        });
    }

    public Optional<CareCase> find(DatabaseSecurityContext actor, UUID id) {
        return transactions.required(actor, () -> repository.find(actor.tenantId(), id));
    }

    /** Legacy PHASE-05 state transition surface. PHASE-11 must use action-code orchestration instead. */
    public CareCase advance(
            DatabaseSecurityContext actor,
            UUID id,
            int expectedVersion,
            String requestedStatus,
            String resultSummary,
            ClosureChecklist closureChecklist) {
        Objects.requireNonNull(actor, "actor");
        return transactions.required(actor, () -> {
            CareCase current = repository
                    .find(actor.tenantId(), id)
                    .orElseThrow(() -> new ProcessRejectedException("care case not found"));
            if (current.versionNo() != expectedVersion) {
                throw new ProcessRejectedException("care case version conflict");
            }
            if (Objects.equals(current.status(), requestedStatus)) {
                return current;
            }
            STATES.requireTransition(current.status(), requestedStatus);
            if ("S05".equals(requestedStatus)) {
                finance().validateBudgetInvoiceAndExecute(current);
            }
            if ("S07".equals(requestedStatus)) {
                finance().reconcile(current);
            }
            if (SequentialStateMachine.CLOSED.equals(requestedStatus)) {
                requireClosure(closureChecklist, resultSummary);
            }
            Instant actualStartAt = current.actualStartAt();
            if (actualStartAt == null && "S05".equals(requestedStatus)) {
                actualStartAt = Instant.now();
            }
            Instant actualEndAt = current.actualEndAt();
            Instant closedAt = current.closedAt();
            if (SequentialStateMachine.CLOSED.equals(requestedStatus)) {
                actualEndAt = Instant.now();
                closedAt = actualEndAt;
            }
            int updated = repository.updateStatus(
                    actor.tenantId(),
                    id,
                    expectedVersion,
                    requestedStatus,
                    resultSummary,
                    actualStartAt,
                    actualEndAt,
                    closedAt,
                    actor.userId());
            if (updated != 1) {
                throw new ProcessRejectedException("care case concurrent update conflict");
            }
            return repository
                    .find(actor.tenantId(), id)
                    .orElseThrow(() -> new ProcessRejectedException("care case disappeared after update"));
        });
    }

    public void validateInvoiceEvidence(DatabaseSecurityContext actor, UUID id, InvoiceEvidence invoice) {
        Objects.requireNonNull(actor, "actor");
        validateInvoice(invoice);
        transactions.required(actor, () -> {
            CareCase current = repository
                    .find(actor.tenantId(), id)
                    .orElseThrow(() -> new ProcessRejectedException("care case not found"));
            finance().assertInvoiceUnique(current, invoice);
            return null;
        });
    }

    /**
     * Reuses the PHASE-05 financial side-effect capability without mutating the legacy target-status state machine.
     * The PHASE-11 canonical workflow remains the only state-transition authority.
     */
    public void executeBenefit(DatabaseSecurityContext actor, UUID id, InvoiceEvidence invoice) {
        Objects.requireNonNull(actor, "actor");
        if (invoice != null) {
            validateInvoice(invoice);
        }
        transactions.required(actor, () -> {
            CareCase current = repository
                    .find(actor.tenantId(), id)
                    .orElseThrow(() -> new ProcessRejectedException("care case not found"));
            FinanceCapability capability = finance();
            if (invoice != null) {
                capability.assertInvoiceUnique(current, invoice);
            }
            capability.validateBudgetInvoiceAndExecute(current);
            return null;
        });
    }

    /** Reuses PHASE-05 reconciliation as a fail-closed side effect without accepting a client target status. */
    public void reconcileBenefit(DatabaseSecurityContext actor, UUID id) {
        Objects.requireNonNull(actor, "actor");
        transactions.required(actor, () -> {
            CareCase current = repository
                    .find(actor.tenantId(), id)
                    .orElseThrow(() -> new ProcessRejectedException("care case not found"));
            finance().reconcile(current);
            return null;
        });
    }

    private FinanceCapability finance() {
        if (financeCapabilities.size() != 1) {
            throw new ProcessRejectedException("welfare finance capability is unavailable or ambiguous");
        }
        return financeCapabilities.getFirst();
    }

    private static void validate(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        if (blank(command.costCenterId())
                || blank(command.currency())
                || blank(command.employeeEventType())
                || command.factOccurredAt() == null
                || blank(command.factSummary())
                || blank(command.impactLevel())) {
            throw new ProcessRejectedException("required care case fields are missing");
        }
        if (command.benefitAmount() != null && command.benefitAmount().signum() < 0) {
            throw new ProcessRejectedException("benefit amount must be non-negative");
        }
    }

    private static void validateInvoice(InvoiceEvidence invoice) {
        Objects.requireNonNull(invoice, "invoice");
        if (invoice.amount() == null
                || invoice.amount().signum() < 0
                || blank(invoice.invoiceCode())
                || blank(invoice.invoiceNumber())
                || invoice.invoiceDate() == null
                || invoice.fileId() == null
                || blank(invoice.imageSha256())
                || invoice.imageSha256().length() != 64) {
            throw new ProcessRejectedException("invoice evidence is incomplete or invalid");
        }
    }

    private static void requireClosure(ClosureChecklist checklist, String resultSummary) {
        if (checklist == null
                || !checklist.requiredTasksComplete()
                || !checklist.settlementReceiptComplete()
                || !checklist.exceptionsResolved()
                || !checklist.archiveComplete()
                || blank(resultSummary)) {
            throw new ProcessRejectedException("care case close conditions are not satisfied");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public interface Repository {
        void insert(CareCase careCase, UUID actorId);

        Optional<CareCase> find(UUID tenantId, UUID id);

        int updateStatus(
                UUID tenantId,
                UUID id,
                int expectedVersion,
                String status,
                String resultSummary,
                Instant actualStartAt,
                Instant actualEndAt,
                Instant closedAt,
                UUID actorId);
    }

    public interface FinanceCapability {
        void validateBudgetInvoiceAndExecute(CareCase careCase);

        void reconcile(CareCase careCase);

        void assertInvoiceUnique(CareCase careCase, InvoiceEvidence invoiceEvidence);
    }

    public record InvoiceEvidence(
            String invoiceCode,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal amount,
            UUID fileId,
            String imageSha256) {}

    public record ClosureChecklist(
            boolean requiredTasksComplete,
            boolean settlementReceiptComplete,
            boolean exceptionsResolved,
            boolean archiveComplete) {}

    public record CreateCommand(
            String sourceChannel,
            LocalDate businessDate,
            String subject,
            String reason,
            String priority,
            String riskLevel,
            UUID ownerCenterId,
            UUID ownerDepartmentId,
            UUID ownerEmployeeId,
            BigDecimal benefitAmount,
            String budgetItemId,
            String costCenterId,
            String currency,
            String employeeEventType,
            Instant factOccurredAt,
            String factSummary,
            LocalDate impactEffectiveDate,
            String impactLevel,
            Long pointsDelta) {}

    public record CareCase(
            UUID id,
            UUID tenantId,
            String businessNo,
            String status,
            int versionNo,
            String sourceChannel,
            LocalDate businessDate,
            String subject,
            String reason,
            String priority,
            String riskLevel,
            UUID ownerCenterId,
            UUID ownerDepartmentId,
            UUID ownerEmployeeId,
            BigDecimal benefitAmount,
            String budgetItemId,
            String costCenterId,
            String currency,
            String employeeEventType,
            Instant factOccurredAt,
            String factSummary,
            LocalDate impactEffectiveDate,
            String impactLevel,
            Long pointsDelta,
            String resultSummary,
            Instant actualStartAt,
            Instant actualEndAt,
            Instant closedAt) {}
}
