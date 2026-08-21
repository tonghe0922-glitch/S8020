package cn.shangjingu.platform.api.phase09;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.NoticeReceiptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PHASE-09 engineering HTTP contract for source process P005. */
@RestController
@RequestMapping("/api/v1/processes/P005/notices")
public final class P005NoticeController {
    public static final String PUBLISH = "p005.notice.publish";
    public static final String READ = "p005.notice.read";
    public static final String RECEIPT = "p005.notice.receipt";
    public static final String MANAGE = "p005.notice.manage";
    public static final String MONITOR = "p005.notice.monitor";

    private final NoticeReceiptService notices;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public P005NoticeController(
            NoticeReceiptService notices, AuthorizationService authorization,
            JdbcSecurityAuditService audit, ObjectMapper mapper) {
        this.notices = notices;
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping
    public NoticeView publish(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NoticeReceiptService.PublishCommand command) {
        require(authorization.authorizeAction(principal.context(), PUBLISH));
        AuthorizationTarget target = new AuthorizationTarget(
                principal.context().tenantId(), principal.context().employeeId(), command.targetCenterId(),
                principal.context().positionId(), principal.context().employeeId());
        require(authorization.authorizeData(principal.context(), PUBLISH, target));
        audit.recordOperation(principal.context(), "P005_PUBLISH_ATTEMPT", "collaboration.notice", null);
        NoticeReceiptService.NoticeAggregate published = notices.publish(
                context(principal), idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P005_PUBLISHED", "collaboration.notice", published.notice().id());
        return view(principal, published, MANAGE);
    }

    @GetMapping
    public List<NoticeView> list(@AuthenticationPrincipal SessionPrincipal principal) {
        boolean canRead = allowed(principal, READ);
        boolean canManage = allowed(principal, MANAGE);
        boolean canMonitor = allowed(principal, MONITOR);
        if (!canRead && !canManage && !canMonitor) throw denied("no P005 read surface is granted");
        List<NoticeView> visible = notices.list(context(principal)).stream()
                .map(aggregate -> viewOrNull(principal, aggregate, canRead, canManage, canMonitor))
                .filter(java.util.Objects::nonNull)
                .toList();
        audit.recordOperation(principal.context(), "P005_LIST", "collaboration.notice", null);
        return visible;
    }

    @GetMapping("/{id}")
    public NoticeView get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        boolean canRead = allowed(principal, READ);
        boolean canManage = allowed(principal, MANAGE);
        boolean canMonitor = allowed(principal, MONITOR);
        if (!canRead && !canManage && !canMonitor) throw denied("no P005 read surface is granted");
        NoticeReceiptService.NoticeAggregate aggregate = required(principal, id);
        NoticeView result = viewOrNull(principal, aggregate, canRead, canManage, canMonitor);
        if (result == null) throw denied("P005 data scope denied");
        audit.recordOperation(principal.context(), "P005_READ", "collaboration.notice", id);
        return result;
    }

    @PostMapping("/{id}/read")
    public NoticeView markRead(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NoticeReceiptService.ReceiptCommand command) {
        require(authorization.authorizeAction(principal.context(), RECEIPT));
        NoticeReceiptService.NoticeAggregate current = required(principal, id);
        NoticeReceiptService.Recipient recipient = ownRecipient(principal, current);
        require(authorization.authorizeData(principal.context(), RECEIPT, target(recipient, principal.context().tenantId())));
        audit.recordOperation(principal.context(), "P005_READ_ATTEMPT", "collaboration.notice", id);
        NoticeReceiptService.NoticeAggregate result = notices.markRead(
                context(principal), id, idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P005_READ_RECEIPT", "collaboration.notice", id);
        return recipientView(result, principal.context().employeeId());
    }

    @PostMapping("/{id}/confirm")
    public NoticeView confirm(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NoticeReceiptService.ReceiptCommand command) {
        require(authorization.authorizeAction(principal.context(), RECEIPT));
        NoticeReceiptService.NoticeAggregate current = required(principal, id);
        NoticeReceiptService.Recipient recipient = ownRecipient(principal, current);
        require(authorization.authorizeData(principal.context(), RECEIPT, target(recipient, principal.context().tenantId())));
        audit.recordOperation(principal.context(), "P005_CONFIRM_ATTEMPT", "collaboration.notice", id);
        NoticeReceiptService.NoticeAggregate result = notices.confirm(
                context(principal), id, idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P005_CONFIRMED", "collaboration.notice", id);
        return recipientView(result, principal.context().employeeId());
    }

    @PostMapping("/{id}/understanding")
    public NoticeView understanding(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NoticeReceiptService.UnderstandingCommand command) {
        require(authorization.authorizeAction(principal.context(), RECEIPT));
        NoticeReceiptService.NoticeAggregate current = required(principal, id);
        NoticeReceiptService.Recipient recipient = ownRecipient(principal, current);
        require(authorization.authorizeData(principal.context(), RECEIPT, target(recipient, principal.context().tenantId())));
        audit.recordOperation(principal.context(), "P005_UNDERSTANDING_ATTEMPT", "collaboration.notice", id);
        NoticeReceiptService.NoticeAggregate result = notices.understanding(
                context(principal), id, idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P005_UNDERSTANDING_RECORDED", "collaboration.notice", id);
        return recipientView(result, principal.context().employeeId());
    }

    @PostMapping("/{id}/execution")
    public NoticeView execution(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NoticeReceiptService.ExecutionCommand command) {
        require(authorization.authorizeAction(principal.context(), RECEIPT));
        NoticeReceiptService.NoticeAggregate current = required(principal, id);
        NoticeReceiptService.Recipient recipient = ownRecipient(principal, current);
        require(authorization.authorizeData(principal.context(), RECEIPT, target(recipient, principal.context().tenantId())));
        audit.recordOperation(principal.context(), "P005_EXECUTION_ATTEMPT", "collaboration.notice", id);
        NoticeReceiptService.NoticeAggregate result = notices.execute(
                context(principal), id, idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P005_EXECUTION_RECORDED", "collaboration.notice", id);
        return recipientView(result, principal.context().employeeId());
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public NoticeView manage(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id, @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NoticeReceiptService.ManageCommand command) {
        require(authorization.authorizeAction(principal.context(), MANAGE));
        NoticeReceiptService.NoticeAggregate current = required(principal, id);
        require(authorization.authorizeData(principal.context(), MANAGE, target(current.notice())));
        String action = safeAction(actionCode);
        audit.recordOperation(principal.context(), "P005_ACTION_ATTEMPT_" + action, "collaboration.notice", id);
        NoticeReceiptService.NoticeAggregate result = notices.manage(
                context(principal), id, action, idempotencyKey, hash(Map.of("actionCode", action, "body", command)), command);
        audit.recordOperation(principal.context(), "P005_ACTION_" + action, "collaboration.notice", id);
        return view(principal, result, MANAGE);
    }

    private NoticeReceiptService.NoticeAggregate required(SessionPrincipal principal, UUID id) {
        return notices.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P005 notice not found"));
    }

    private NoticeView viewOrNull(
            SessionPrincipal principal, NoticeReceiptService.NoticeAggregate aggregate,
            boolean canRead, boolean canManage, boolean canMonitor) {
        if (canManage && authorization.authorizeData(principal.context(), MANAGE, target(aggregate.notice())).allowed()) {
            return view(principal, aggregate, MANAGE);
        }
        NoticeReceiptService.Recipient own = aggregate.recipients().stream()
                .filter(r -> principal.context().employeeId().equals(r.employeeId())).findFirst().orElse(null);
        if (canRead && own != null
                && authorization.authorizeData(principal.context(), READ, target(own, aggregate.notice().tenantId())).allowed()) {
            return recipientView(aggregate, principal.context().employeeId());
        }
        if (canMonitor && authorization.authorizeData(principal.context(), MONITOR, target(aggregate.notice())).allowed()) {
            return metadataView(aggregate);
        }
        return null;
    }

    private NoticeView view(SessionPrincipal principal, NoticeReceiptService.NoticeAggregate aggregate, String permission) {
        require(authorization.authorizeData(principal.context(), permission, target(aggregate.notice())));
        return fullView(aggregate);
    }

    private static NoticeView fullView(NoticeReceiptService.NoticeAggregate aggregate) {
        return new NoticeView(
                aggregate.notice(), aggregate.recipients(), aggregate.recipientCount(), aggregate.deliveredCount(),
                aggregate.readCount(), aggregate.confirmedCount(), aggregate.understandingPassedCount(),
                aggregate.executedCount(), aggregate.acceptedCount());
    }

    private static NoticeView recipientView(NoticeReceiptService.NoticeAggregate aggregate, UUID employeeId) {
        NoticeReceiptService.NoticeAggregate personal = aggregate.recipientView(employeeId);
        return new NoticeView(
                personal.notice(), personal.recipients(), aggregate.recipientCount(), aggregate.deliveredCount(),
                aggregate.readCount(), aggregate.confirmedCount(), aggregate.understandingPassedCount(),
                aggregate.executedCount(), aggregate.acceptedCount());
    }

    private static NoticeView metadataView(NoticeReceiptService.NoticeAggregate aggregate) {
        NoticeReceiptService.NoticeAggregate metadata = aggregate.metadataOnly();
        return new NoticeView(
                metadata.notice(), List.of(), aggregate.recipientCount(), aggregate.deliveredCount(),
                aggregate.readCount(), aggregate.confirmedCount(), aggregate.understandingPassedCount(),
                aggregate.executedCount(), aggregate.acceptedCount());
    }

    private static NoticeReceiptService.Recipient ownRecipient(
            SessionPrincipal principal, NoticeReceiptService.NoticeAggregate aggregate) {
        return aggregate.recipients().stream()
                .filter(r -> principal.context().employeeId().equals(r.employeeId())).findFirst()
                .orElseThrow(() -> denied("P005 employee is not a resolved recipient"));
    }

    private static AuthorizationTarget target(NoticeReceiptService.Notice notice) {
        return new AuthorizationTarget(
                notice.tenantId(), notice.ownerEmployeeId(), notice.targetCenterId(), null, notice.ownerEmployeeId());
    }

    private static AuthorizationTarget target(NoticeReceiptService.Recipient recipient, UUID tenantId) {
        return new AuthorizationTarget(
                tenantId, recipient.employeeId(), recipient.orgId(), recipient.positionId(), recipient.employeeId());
    }

    private boolean allowed(SessionPrincipal principal, String permission) {
        return authorization.authorizeAction(principal.context(), permission).allowed();
    }

    private static DatabaseSecurityContext context(SessionPrincipal principal) {
        var s = principal.context();
        return new DatabaseSecurityContext(
                s.tenantId(), s.userId(), s.identityId(), s.employeeId(), s.appointmentId(), s.orgId(), s.positionId());
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) throw denied(decision.reason().toString());
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("P005 authorization denied: " + reason);
    }

    private static String safeAction(String actionCode) {
        if (actionCode == null) return "INVALID";
        String value = actionCode.trim().toUpperCase();
        return value.matches("[A-Z0-9_]{1,32}") ? value : "INVALID";
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("P005 request cannot be hashed", ex);
        }
    }

    public record NoticeView(
            NoticeReceiptService.Notice notice,
            List<NoticeReceiptService.Recipient> recipients,
            int recipientCount,
            long deliveredCount,
            long readCount,
            long confirmedCount,
            long understandingPassedCount,
            long executedCount,
            long acceptedCount) {}
}
