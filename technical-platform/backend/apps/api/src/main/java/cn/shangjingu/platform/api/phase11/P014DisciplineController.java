package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.phase11.DisciplineService;
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
@RequestMapping("/api/v1/processes/P014/discipline-cases")
public final class P014DisciplineController {
    public static final String CREATE = "p014.discipline.create";
    public static final String READ = "p014.discipline.read";
    public static final String INVESTIGATE = "p014.discipline.investigate";
    public static final String DECIDE = "p014.discipline.decide";
    public static final String APPEAL = "p014.discipline.appeal";
    public static final String REMEDIATE = "p014.discipline.remediate";
    public static final String MONITOR = "p014.discipline.monitor";

    private final DisciplineService discipline;
    private final Phase11ApiSupport support;

    public P014DisciplineController(DisciplineService discipline, Phase11ApiSupport support) {
        this.discipline = discipline;
        this.support = support;
    }

    @PostMapping
    public Phase11Record create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DisciplineService.CreateCommand command) {
        support.requireAction(principal, CREATE, "P014");
        support.requireData(
                principal,
                CREATE,
                support.target(principal, command.ownerCenterId(), command.ownerEmployeeId()),
                "P014");
        support.audit(principal, "P014_CREATE_ATTEMPT", "reward.discipline_case", null);
        Phase11Record result = discipline.create(
                support.context(principal),
                idempotencyKey,
                support.hash(command, "P014"),
                command);
        support.audit(principal, "P014_CREATED", "reward.discipline_case", result.id());
        return result;
    }

    @GetMapping
    public List<Phase11Record> list(@AuthenticationPrincipal SessionPrincipal principal) {
        boolean read = support.allowed(principal, READ);
        boolean manage = manageAllowed(principal);
        boolean monitor = support.allowed(principal, MONITOR);
        if (!read && !manage && !monitor) {
            throw denied("no P014 read surface is granted");
        }
        return discipline.list(support.context(principal)).stream()
                .map(record -> project(principal, record, read, manage, monitor))
                .filter(Objects::nonNull)
                .toList();
    }

    @GetMapping("/{id}")
    public Phase11Record get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        Phase11Record record = required(principal, id);
        Phase11Record projected = project(
                principal,
                record,
                support.allowed(principal, READ),
                manageAllowed(principal),
                support.allowed(principal, MONITOR));
        if (projected == null) {
            throw denied("P014 data scope denied");
        }
        support.audit(principal, "P014_READ", "reward.discipline_case", id);
        return projected;
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11Record action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody DisciplineService.ActionCommand command) {
        String action = support.safeAction(actionCode);
        String permission = actionPermission(action);
        support.requireAction(principal, permission, "P014");
        Phase11Record current = required(principal, id);
        support.requireData(principal, permission, support.target(principal, current), "P014");
        support.audit(principal, "P014_ACTION_ATTEMPT_" + action, "reward.discipline_case", id);
        Phase11Record result = discipline.act(
                support.context(principal),
                id,
                action,
                idempotencyKey,
                support.hash(Map.of("action", action, "body", command), "P014"),
                command);
        support.audit(principal, "P014_ACTION_" + action, "reward.discipline_case", id);
        return result;
    }

    private Phase11Record project(
            SessionPrincipal principal,
            Phase11Record record,
            boolean read,
            boolean manage,
            boolean monitor) {
        AuthorizationTarget target = support.target(principal, record);
        if (manage && anyManageData(principal, target)) {
            return record;
        }
        if (read && support.allowedData(principal, READ, target)) {
            return record;
        }
        if (monitor && support.allowedData(principal, MONITOR, target)) {
            return record.metadataOnly();
        }
        return null;
    }

    private boolean manageAllowed(SessionPrincipal principal) {
        return support.allowed(principal, INVESTIGATE)
                || support.allowed(principal, DECIDE)
                || support.allowed(principal, APPEAL)
                || support.allowed(principal, REMEDIATE);
    }

    private boolean anyManageData(SessionPrincipal principal, AuthorizationTarget target) {
        return support.allowedData(principal, INVESTIGATE, target)
                || support.allowedData(principal, DECIDE, target)
                || support.allowedData(principal, APPEAL, target)
                || support.allowedData(principal, REMEDIATE, target);
    }

    private Phase11Record required(SessionPrincipal principal, UUID id) {
        return discipline.find(support.context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P014 discipline case not found"));
    }

    static String actionPermission(String action) {
        return switch (action) {
            case "APPLY_SAFETY_MEASURE", "COMPLETE_INVESTIGATION" -> INVESTIGATE;
            case "COMPLETE_RESPONSIBILITY_REVIEW", "APPROVE_DECISION" -> DECIDE;
            case "SUBMIT_DEFENSE", "ACKNOWLEDGE_SERVICE", "RESOLVE_APPEAL" -> APPEAL;
            case "EXECUTE_IMPACTS", "CLOSE_CORE_CASE", "COMPLETE_OBSERVATION", "ARCHIVE" -> REMEDIATE;
            default -> throw new IllegalArgumentException("P014 action is invalid");
        };
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("P014 authorization denied: " + reason);
    }
}
