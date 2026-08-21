package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.phase11.Phase11Record;
import cn.shangjingu.platform.workflow.phase11.RewardService;
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
@RequestMapping("/api/v1/processes/P013/rewards")
public final class P013RewardController {
    public static final String CREATE = "p013.reward.create";
    public static final String READ = "p013.reward.read";
    public static final String REVIEW = "p013.reward.review";
    public static final String EXECUTE = "p013.reward.execute";
    public static final String MONITOR = "p013.reward.monitor";

    private final RewardService rewards;
    private final Phase11ApiSupport support;

    public P013RewardController(RewardService rewards, Phase11ApiSupport support) {
        this.rewards = rewards;
        this.support = support;
    }

    @PostMapping
    public Phase11Record create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RewardService.CreateCommand command) {
        support.requireAction(principal, CREATE, "P013");
        support.requireData(
                principal,
                CREATE,
                support.target(principal, command.ownerCenterId(), command.ownerEmployeeId()),
                "P013");
        support.audit(principal, "P013_CREATE_ATTEMPT", "reward.reward_case", null);
        Phase11Record result = rewards.create(
                support.context(principal),
                idempotencyKey,
                support.hash(command, "P013"),
                command);
        support.audit(principal, "P013_CREATED", "reward.reward_case", result.id());
        return result;
    }

    @GetMapping
    public List<Phase11Record> list(
            @AuthenticationPrincipal SessionPrincipal principal) {
        boolean read = support.allowed(principal, READ);
        boolean manage = manageAllowed(principal);
        boolean monitor = support.allowed(principal, MONITOR);
        if (!read && !manage && !monitor) {
            throw denied("no P013 read surface is granted");
        }
        return rewards.list(support.context(principal)).stream()
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
            throw denied("P013 data scope denied");
        }
        support.audit(principal, "P013_READ", "reward.reward_case", id);
        return projected;
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11Record action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RewardService.ActionCommand command) {
        String action = support.safeAction(actionCode);
        String permission = actionPermission(action);
        support.requireAction(principal, permission, "P013");
        Phase11Record current = required(principal, id);
        support.requireData(principal, permission, support.target(principal, current), "P013");
        support.audit(
                principal,
                "P013_ACTION_ATTEMPT_" + action,
                "reward.reward_case",
                id);
        Phase11Record result = rewards.act(
                support.context(principal),
                id,
                action,
                idempotencyKey,
                support.hash(Map.of("action", action, "body", command), "P013"),
                command);
        support.audit(
                principal,
                "P013_ACTION_" + action,
                "reward.reward_case",
                id);
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
        return support.allowed(principal, REVIEW) || support.allowed(principal, EXECUTE);
    }

    private boolean anyManageData(
            SessionPrincipal principal, AuthorizationTarget target) {
        return support.allowedData(principal, REVIEW, target)
                || support.allowedData(principal, EXECUTE, target);
    }

    private Phase11Record required(SessionPrincipal principal, UUID id) {
        return rewards.find(support.context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P013 reward case not found"));
    }

    static String actionPermission(String action) {
        return switch (action) {
            case "VERIFY_EVIDENCE", "RECOMMEND_REWARD", "APPROVE_REWARD",
                    "CHECK_DUPLICATE_IMPACT" -> REVIEW;
            case "EXECUTE_REWARD", "NOTIFY_EMPLOYEE", "RECORD_RECEIPTS", "ARCHIVE" -> EXECUTE;
            default -> throw new IllegalArgumentException("P013 action is invalid");
        };
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("P013 authorization denied: " + reason);
    }
}
