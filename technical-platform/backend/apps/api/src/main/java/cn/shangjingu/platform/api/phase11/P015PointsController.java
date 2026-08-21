package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.phase11.PointLedgerService;
import cn.shangjingu.platform.workflow.phase11.PointLedgerView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
@RequestMapping("/api/v1/processes/P015/points")
public final class P015PointsController {
    public static final String CREATE = "p015.points.create";
    public static final String READ = "p015.points.read";
    public static final String REVIEW = "p015.points.review";
    public static final String REVERSE = "p015.points.reverse";
    public static final String MONITOR = "p015.points.monitor";

    private final PointLedgerService points;
    private final Phase11ApiSupport support;

    public P015PointsController(PointLedgerService points, Phase11ApiSupport support) {
        this.points = points;
        this.support = support;
    }

    @PostMapping
    public PointLedgerView create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PointLedgerService.CreateCommand command) {
        support.requireAction(principal, CREATE, "P015");
        support.requireData(principal, CREATE,
                support.target(principal, command.ownerCenterId(), command.ownerEmployeeId()), "P015");
        support.audit(principal, "P015_CREATE_ATTEMPT", "reward.point_transaction", null);
        PointLedgerView result = points.create(
                support.context(principal), idempotencyKey, support.hash(command, "P015"), command);
        support.audit(principal, "P015_CREATED", "reward.point_transaction", result.id());
        return result;
    }

    @GetMapping
    public List<PointLedgerView> list(@AuthenticationPrincipal SessionPrincipal principal) {
        boolean read = support.allowed(principal, READ);
        boolean manage = manageAllowed(principal);
        boolean monitor = support.allowed(principal, MONITOR);
        if (!read && !manage && !monitor) throw denied("no P015 read surface is granted");
        return points.list(support.context(principal)).stream()
                .map(record -> project(principal, record, read, manage, monitor))
                .filter(Objects::nonNull)
                .toList();
    }

    @GetMapping("/{id}")
    public PointLedgerView get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        PointLedgerView record = required(principal, id);
        PointLedgerView projected = project(principal, record,
                support.allowed(principal, READ), manageAllowed(principal), support.allowed(principal, MONITOR));
        if (projected == null) throw denied("P015 data scope denied");
        support.audit(principal, "P015_READ", "reward.point_transaction", id);
        return projected;
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public PointLedgerView action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PointLedgerService.ActionCommand command) {
        String action = support.safeAction(actionCode);
        String permission = actionPermission(action);
        support.requireAction(principal, permission, "P015");
        PointLedgerView current = required(principal, id);
        support.requireData(principal, permission,
                support.target(principal, current.ownerCenterId(), current.ownerEmployeeId()), "P015");
        support.audit(principal, "P015_ACTION_ATTEMPT_" + action, "reward.point_transaction", id);
        PointLedgerView result = points.act(
                support.context(principal), id, action, idempotencyKey,
                support.hash(Map.of("action", action, "body", command), "P015"), command);
        support.audit(principal, "P015_ACTION_" + action, "reward.point_transaction", id);
        return result;
    }

    private PointLedgerView project(
            SessionPrincipal principal, PointLedgerView record,
            boolean read, boolean manage, boolean monitor) {
        AuthorizationTarget target = support.target(principal, record.ownerCenterId(), record.ownerEmployeeId());
        if (manage && anyManageData(principal, target)) return record;
        if (read && support.allowedData(principal, READ, target)) return record;
        if (monitor && support.allowedData(principal, MONITOR, target)) return record.metadataOnly();
        return null;
    }

    private boolean manageAllowed(SessionPrincipal principal) {
        return support.allowed(principal, REVIEW) || support.allowed(principal, REVERSE);
    }

    private boolean anyManageData(SessionPrincipal principal, AuthorizationTarget target) {
        return support.allowedData(principal, REVIEW, target) || support.allowedData(principal, REVERSE, target);
    }

    private PointLedgerView required(SessionPrincipal principal, UUID id) {
        return points.find(support.context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P015 point workflow case not found"));
    }

    static String actionPermission(String action) {
        return switch (action) {
            case "VALIDATE_SOURCE", "CHECK_DUPLICATE", "MATCH_RULE_VERSION", "CALCULATE_POINTS",
                    "CLASSIFY_RISK", "POST_OR_REVIEW", "NOTIFY_EMPLOYEE" -> REVIEW;
            case "ADJUST_OR_REVERSE", "RECALCULATE_BALANCE" -> REVERSE;
            default -> throw new IllegalArgumentException("P015 action is invalid");
        };
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("P015 authorization denied: " + reason);
    }
}
