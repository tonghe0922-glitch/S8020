package cn.shangjingu.platform.api.phase10;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.LeaveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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

@RestController
@RequestMapping("/api/v1/processes/P008")
public class P008LeaveController {
    public static final String SUBMIT = "p008.leave.submit",
            READ = "p008.leave.read",
            REVIEW = "p008.leave.review",
            MANAGE = "p008.leave.manage",
            MONITOR = "p008.leave.monitor";
    private final LeaveService leaves;
    private final AuthorizationService auth;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public P008LeaveController(
            LeaveService leaves, AuthorizationService auth, JdbcSecurityAuditService audit, ObjectMapper mapper) {
        this.leaves = leaves;
        this.auth = auth;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping("/leaves")
    public LeaveService.Aggregate create(
            @AuthenticationPrincipal SessionPrincipal p,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody LeaveService.CreateCommand c) {
        require(auth.authorizeAction(p.context(), SUBMIT));
        require(auth.authorizeData(
                p.context(),
                SUBMIT,
                new AuthorizationTarget(
                        p.context().tenantId(),
                        p.context().employeeId(),
                        c.ownerCenterId(),
                        p.context().positionId(),
                        p.context().employeeId())));
        audit.recordOperation(p.context(), "P008_CREATE_ATTEMPT", "attendance.leave_request", null);
        var r = leaves.create(ctx(p), key, hash(c), c);
        audit.recordOperation(
                p.context(),
                "P008_CREATED",
                "attendance.leave_request",
                r.record().id());
        return r;
    }

    @GetMapping("/leaves")
    public List<LeaveService.Aggregate> list(@AuthenticationPrincipal SessionPrincipal p) {
        boolean read = allowed(p, READ),
                manage = allowed(p, MANAGE),
                review = allowed(p, REVIEW),
                monitor = allowed(p, MONITOR);
        if (!read && !manage && !review && !monitor) throw denied("no P008 read surface");
        List<LeaveService.Aggregate> result = leaves.list(ctx(p)).stream()
                .map(a -> project(p, a, read, manage, review, monitor))
                .filter(java.util.Objects::nonNull)
                .toList();
        audit.recordOperation(p.context(), "P008_LIST", "attendance.leave_request", null);
        return result;
    }

    @GetMapping("/leaves/{id}")
    public LeaveService.Aggregate get(@AuthenticationPrincipal SessionPrincipal p, @PathVariable UUID id) {
        boolean read = allowed(p, READ),
                manage = allowed(p, MANAGE),
                review = allowed(p, REVIEW),
                monitor = allowed(p, MONITOR);
        if (!read && !manage && !review && !monitor) throw denied("no P008 read surface");
        var a = leaves.find(ctx(p), id).orElseThrow(() -> new IllegalArgumentException("P008 leave not found"));
        var projected = project(p, a, read, manage, review, monitor);
        if (projected == null) throw denied("P008 data scope denied");
        audit.recordOperation(p.context(), "P008_READ", "attendance.leave_request", id);
        return projected;
    }

    @GetMapping("/quota-ledger")
    public List<LeaveService.LedgerEntry> ledger(@AuthenticationPrincipal SessionPrincipal p) {
        boolean read = allowed(p, READ),
                manage = allowed(p, MANAGE),
                review = allowed(p, REVIEW),
                monitor = allowed(p, MONITOR);
        if (!read && !manage && !review && !monitor) throw denied("no P008 ledger surface");
        List<LeaveService.LedgerEntry> result = leaves.ledger(ctx(p)).stream()
                .map(e -> projectLedger(p, e, read, manage, review, monitor))
                .filter(java.util.Objects::nonNull)
                .toList();
        audit.recordOperation(p.context(), "P008_LEDGER_READ", "attendance.leave_request_item", null);
        return result;
    }

    @PostMapping("/leaves/{id}/actions/{actionCode}")
    public LeaveService.Aggregate action(
            @AuthenticationPrincipal SessionPrincipal p,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody LeaveService.ActionCommand c) {
        String action = safe(actionCode);
        String permission = permission(action);
        require(auth.authorizeAction(p.context(), permission));
        var current = leaves.find(ctx(p), id).orElseThrow(() -> new IllegalArgumentException("P008 leave not found"));
        require(auth.authorizeData(p.context(), permission, target(current.record(), p)));
        if (SUBMIT.equals(permission)
                && !p.context().employeeId().equals(current.record().ownerEmployeeId()))
            throw denied("P008 employee action is self-only");
        audit.recordOperation(p.context(), "P008_ACTION_ATTEMPT_" + action, "attendance.leave_request", id);
        var result = leaves.act(ctx(p), id, action, key, hash(Map.of("action", action, "body", c)), c);
        audit.recordOperation(p.context(), "P008_ACTION_" + action, "attendance.leave_request", id);
        return result;
    }

    private LeaveService.Aggregate project(
            SessionPrincipal p,
            LeaveService.Aggregate a,
            boolean read,
            boolean manage,
            boolean review,
            boolean monitor) {
        AuthorizationTarget target = target(a.record(), p);
        if (manage && auth.authorizeData(p.context(), MANAGE, target).allowed()) return a;
        if (review && auth.authorizeData(p.context(), REVIEW, target).allowed()) return a;
        if (read
                && p.context().employeeId().equals(a.record().ownerEmployeeId())
                && auth.authorizeData(p.context(), READ, target).allowed()) return a;
        if (monitor && auth.authorizeData(p.context(), MONITOR, target).allowed()) return a.metadataOnly();
        return null;
    }

    private LeaveService.LedgerEntry projectLedger(
            SessionPrincipal p,
            LeaveService.LedgerEntry e,
            boolean read,
            boolean manage,
            boolean review,
            boolean monitor) {
        AuthorizationTarget target = new AuthorizationTarget(
                p.context().tenantId(),
                e.ownerEmployeeId(),
                e.ownerCenterId(),
                p.context().positionId(),
                e.ownerEmployeeId());
        if (manage && auth.authorizeData(p.context(), MANAGE, target).allowed()) return e;
        if (review && auth.authorizeData(p.context(), REVIEW, target).allowed()) return e;
        if (read
                && p.context().employeeId().equals(e.ownerEmployeeId())
                && auth.authorizeData(p.context(), READ, target).allowed()) return e;
        if (monitor && auth.authorizeData(p.context(), MONITOR, target).allowed()) return e.metadataOnly();
        return null;
    }

    private static String permission(String action) {
        return switch (action) {
            case "CONFIRM_HANDOVER", "START_LEAVE", "RETURN_TO_WORK", "CHANGE_LEAVE" -> SUBMIT;
            case "APPROVE_LEAVE", "REJECT_LEAVE" -> REVIEW;
            default -> MANAGE;
        };
    }

    private static AuthorizationTarget target(LeaveService.LeaveRecord r, SessionPrincipal p) {
        return new AuthorizationTarget(
                r.tenantId(),
                r.ownerEmployeeId(),
                r.ownerCenterId(),
                p.context().positionId(),
                r.ownerEmployeeId());
    }

    private boolean allowed(SessionPrincipal p, String permission) {
        return auth.authorizeAction(p.context(), permission).allowed();
    }

    private static DatabaseSecurityContext ctx(SessionPrincipal p) {
        var s = p.context();
        return new DatabaseSecurityContext(
                s.tenantId(), s.userId(), s.identityId(), s.employeeId(), s.appointmentId(), s.orgId(), s.positionId());
    }

    private static void require(AuthorizationDecision d) {
        if (!d.allowed()) throw denied(d.reason().toString());
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("P008 authorization denied: " + reason);
    }

    private static String safe(String action) {
        if (action == null) return "INVALID";
        String x = action.trim().toUpperCase(Locale.ROOT);
        return x.matches("[A-Z0-9_]{1,32}") ? x : "INVALID";
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (Exception e) {
            throw new IllegalArgumentException("P008 request cannot be hashed", e);
        }
    }
}
