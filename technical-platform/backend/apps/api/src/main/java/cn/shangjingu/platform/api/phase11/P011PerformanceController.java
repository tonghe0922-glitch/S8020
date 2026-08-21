package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.phase11.PerformanceService;
import cn.shangjingu.platform.workflow.phase11.Phase11Record;
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
@RequestMapping("/api/v1/processes/P011/performance-cycles")
public final class P011PerformanceController {
    public static final String CREATE = "p011.performance.create";
    public static final String READ = "p011.performance.read";
    public static final String SELF = "p011.performance.self";
    public static final String EVALUATE = "p011.performance.evaluate";
    public static final String CALIBRATE = "p011.performance.calibrate";
    public static final String APPEAL = "p011.performance.appeal";
    public static final String IMPACT = "p011.performance.impact";
    public static final String MONITOR = "p011.performance.monitor";

    private final PerformanceService performance;
    private final Phase11ApiSupport support;

    public P011PerformanceController(
            PerformanceService performance, Phase11ApiSupport support) {
        this.performance = performance;
        this.support = support;
    }

    @PostMapping
    public Phase11Record create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PerformanceService.CreateCommand command) {
        support.requireAction(principal, CREATE, "P011");
        support.requireData(
                principal,
                CREATE,
                support.target(
                        principal,
                        command.ownerCenterId(),
                        command.ownerEmployeeId()),
                "P011");
        support.audit(
                principal,
                "P011_CREATE_ATTEMPT",
                "performance.performance_cycle",
                null);
        Phase11Record result = performance.create(
                support.context(principal),
                idempotencyKey,
                support.hash(command, "P011"),
                command);
        support.audit(
                principal,
                "P011_CREATED",
                "performance.performance_cycle",
                result.id());
        return result;
    }

    @GetMapping
    public List<Phase11Record> list(
            @AuthenticationPrincipal SessionPrincipal principal) {
        boolean self = support.allowed(principal, SELF)
                || support.allowed(principal, READ);
        boolean manage = manageAllowed(principal);
        boolean monitor = support.allowed(principal, MONITOR);
        if (!self && !manage && !monitor) {
            throw denied("no P011 read surface is granted");
        }
        return performance.list(support.context(principal)).stream()
                .map(record -> project(principal, record, self, manage, monitor))
                .filter(Objects::nonNull)
                .toList();
    }

    @GetMapping("/{id}")
    public Phase11Record get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        Phase11Record record = required(principal, id);
        boolean self = support.allowed(principal, SELF)
                || support.allowed(principal, READ);
        boolean manage = manageAllowed(principal);
        boolean monitor = support.allowed(principal, MONITOR);
        Phase11Record projected = project(principal, record, self, manage, monitor);
        if (projected == null) {
            throw denied("P011 data scope denied");
        }
        support.audit(
                principal,
                "P011_READ",
                "performance.performance_cycle",
                id);
        return projected;
    }

    @PostMapping("/{id}/scores/{scoreType}")
    public Phase11Record submitScore(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String scoreType,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PerformanceService.ScoreCommand command) {
        String normalized = support.safeAction(scoreType);
        String permission = scorePermission(normalized);
        support.requireAction(principal, permission, "P011");
        Phase11Record current = required(principal, id);
        support.requireData(
                principal,
                permission,
                support.target(principal, current),
                "P011");
        support.audit(
                principal,
                "P011_SCORE_ATTEMPT_" + normalized,
                "performance.performance_cycle",
                id);
        Phase11Record result = performance.submitScore(
                support.context(principal),
                id,
                normalized,
                idempotencyKey,
                support.hash(
                        Map.of("scoreType", normalized, "body", command),
                        "P011"),
                command);
        support.audit(
                principal,
                "P011_SCORE_" + normalized,
                "performance.performance_cycle",
                id);
        return result;
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11Record action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PerformanceService.ActionCommand command) {
        String action = support.safeAction(actionCode);
        String permission = actionPermission(action);
        support.requireAction(principal, permission, "P011");
        Phase11Record current = required(principal, id);
        support.requireData(
                principal,
                permission,
                support.target(principal, current),
                "P011");
        support.audit(
                principal,
                "P011_ACTION_ATTEMPT_" + action,
                "performance.performance_cycle",
                id);
        Phase11Record result = performance.act(
                support.context(principal),
                id,
                action,
                idempotencyKey,
                support.hash(Map.of("action", action, "body", command), "P011"),
                command);
        support.audit(
                principal,
                "P011_ACTION_" + action,
                "performance.performance_cycle",
                id);
        return result;
    }

    private Phase11Record project(
            SessionPrincipal principal,
            Phase11Record record,
            boolean self,
            boolean manage,
            boolean monitor) {
        AuthorizationTarget target = support.target(principal, record);
        if (manage && anyManageData(principal, target)) {
            return record;
        }
        if (self
                && principal.context().employeeId().equals(record.ownerEmployeeId())
                && (support.allowedData(principal, SELF, target)
                        || support.allowedData(principal, READ, target))) {
            return record;
        }
        if (monitor && support.allowedData(principal, MONITOR, target)) {
            return record.metadataOnly();
        }
        return null;
    }

    private boolean manageAllowed(SessionPrincipal principal) {
        return support.allowed(principal, EVALUATE)
                || support.allowed(principal, CALIBRATE)
                || support.allowed(principal, APPEAL)
                || support.allowed(principal, IMPACT);
    }

    private boolean anyManageData(
            SessionPrincipal principal, AuthorizationTarget target) {
        return support.allowedData(principal, EVALUATE, target)
                || support.allowedData(principal, CALIBRATE, target)
                || support.allowedData(principal, APPEAL, target)
                || support.allowedData(principal, IMPACT, target);
    }

    private Phase11Record required(SessionPrincipal principal, UUID id) {
        return performance
                .find(support.context(principal), id)
                .orElseThrow(() ->
                        new IllegalArgumentException("P011 performance cycle not found"));
    }

    private static String scorePermission(String scoreType) {
        return switch (scoreType) {
            case "EMPLOYEE" -> SELF;
            case "SUPERVISOR", "AUTHORITATIVE" -> EVALUATE;
            case "CALIBRATED" -> CALIBRATE;
            default -> throw new IllegalArgumentException("P011 score type is invalid");
        };
    }

    private static String actionPermission(String action) {
        return switch (action) {
            case "CONFIRM_TARGETS", "SUBMIT_APPEAL_DECISION" -> SELF;
            case "RECORD_COACHING", "COLLECT_FACTS", "SUBMIT_REVIEWS", "CALCULATE_SCORE" ->
                    EVALUATE;
            case "CALIBRATE" -> CALIBRATE;
            case "RESOLVE_APPEAL" -> APPEAL;
            case "EXECUTE_IMPACT", "ARCHIVE" -> IMPACT;
            default -> throw new IllegalArgumentException("P011 action is invalid");
        };
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("P011 authorization denied: " + reason);
    }
}
