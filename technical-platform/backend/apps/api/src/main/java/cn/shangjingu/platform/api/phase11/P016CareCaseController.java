package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.phase11.Phase11CareCaseService;
import cn.shangjingu.platform.workflow.phase11.Phase11CareCaseView;
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
@RequestMapping("/api/v1/processes/P016/care-cases")
public final class P016CareCaseController {
    public static final String CREATE = "p016.care.create";
    public static final String READ = "p016.care.read";
    public static final String REVIEW = "p016.care.review";
    public static final String EXECUTE = "p016.care.execute";
    public static final String CONFIRM = "p016.care.confirm";
    public static final String RECONCILE = "p016.care.reconcile";
    public static final String MONITOR = "p016.care.monitor";

    private final Phase11CareCaseService careCases;
    private final Phase11ApiSupport support;

    public P016CareCaseController(Phase11CareCaseService careCases, Phase11ApiSupport support) {
        this.careCases = careCases;
        this.support = support;
    }

    @PostMapping
    public Phase11CareCaseView create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Phase11CareCaseService.CreateCommand command) {
        support.requireAction(principal, CREATE, "P016");
        support.requireData(principal, CREATE,
                support.target(principal, command.ownerCenterId(), command.ownerEmployeeId()), "P016");
        support.audit(principal, "P016_CREATE_ATTEMPT", "welfare.care_case", null);
        Phase11CareCaseView result = careCases.create(
                support.context(principal), idempotencyKey, support.hash(command, "P016"), command);
        support.audit(principal, "P016_CREATED", "welfare.care_case", result.id());
        return result;
    }

    @GetMapping
    public List<Phase11CareCaseView> list(@AuthenticationPrincipal SessionPrincipal principal) {
        boolean read = support.allowed(principal, READ);
        boolean manage = manageAllowed(principal);
        boolean monitor = support.allowed(principal, MONITOR);
        if (!read && !manage && !monitor) throw denied("no P016 read surface is granted");
        return careCases.list(support.context(principal)).stream()
                .map(record -> project(principal, record, read, manage, monitor))
                .filter(Objects::nonNull)
                .toList();
    }

    @GetMapping("/{id}")
    public Phase11CareCaseView get(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        Phase11CareCaseView record = required(principal, id);
        Phase11CareCaseView projected = project(principal, record,
                support.allowed(principal, READ), manageAllowed(principal), support.allowed(principal, MONITOR));
        if (projected == null) throw denied("P016 data scope denied");
        support.audit(principal, "P016_READ", "welfare.care_case", id);
        return projected;
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11CareCaseView action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Phase11CareCaseService.ActionCommand command) {
        String action = support.safeAction(actionCode);
        String permission = actionPermission(action);
        support.requireAction(principal, permission, "P016");
        Phase11CareCaseView current = required(principal, id);
        support.requireData(principal, permission,
                support.target(principal, current.ownerCenterId(), current.ownerEmployeeId()), "P016");
        support.audit(principal, "P016_ACTION_ATTEMPT_" + action, "welfare.care_case", id);
        Phase11CareCaseView result = careCases.act(
                support.context(principal), id, action, idempotencyKey,
                support.hash(Map.of("action", action, "body", command), "P016"), command);
        support.audit(principal, "P016_ACTION_" + action, "welfare.care_case", id);
        return result;
    }

    private Phase11CareCaseView project(
            SessionPrincipal principal,
            Phase11CareCaseView record,
            boolean read,
            boolean manage,
            boolean monitor) {
        AuthorizationTarget target = support.target(principal, record.ownerCenterId(), record.ownerEmployeeId());
        if (manage && anyManageData(principal, target)) return record;
        if (read && support.allowedData(principal, READ, target)) return record;
        if (monitor && support.allowedData(principal, MONITOR, target)) return record.metadataOnly();
        return null;
    }

    private boolean manageAllowed(SessionPrincipal principal) {
        return support.allowed(principal, REVIEW)
                || support.allowed(principal, EXECUTE)
                || support.allowed(principal, CONFIRM)
                || support.allowed(principal, RECONCILE);
    }

    private boolean anyManageData(SessionPrincipal principal, AuthorizationTarget target) {
        return support.allowedData(principal, REVIEW, target)
                || support.allowedData(principal, EXECUTE, target)
                || support.allowedData(principal, CONFIRM, target)
                || support.allowedData(principal, RECONCILE, target);
    }

    private Phase11CareCaseView required(SessionPrincipal principal, UUID id) {
        return careCases.find(support.context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P016 care workflow case not found"));
    }

    static String actionPermission(String action) {
        return switch (action) {
            case "VERIFY_ELIGIBILITY", "APPROVE_CARE" -> REVIEW;
            case "AUTHORIZE_PRIVACY", "CONFIRM_RECEIPT" -> CONFIRM;
            case "EXECUTE_BENEFIT", "ARCHIVE" -> EXECUTE;
            case "RECONCILE" -> RECONCILE;
            default -> throw new IllegalArgumentException("P016 action is invalid");
        };
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("P016 authorization denied: " + reason);
    }
}
