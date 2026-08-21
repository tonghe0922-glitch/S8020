package cn.shangjingu.platform.api.phase11;

import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.phase11.Phase11Record;
import cn.shangjingu.platform.workflow.phase11.PromotionService;
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
@RequestMapping("/api/v1/processes/P012/promotions")
public final class P012PromotionController {
    public static final String CREATE = "p012.promotion.create";
    public static final String READ = "p012.promotion.read";
    public static final String REVIEW = "p012.promotion.review";
    public static final String APPOINT = "p012.promotion.appoint";
    public static final String ACTIVATE = "p012.promotion.activate";
    public static final String MONITOR = "p012.promotion.monitor";

    private final PromotionService promotions;
    private final Phase11ApiSupport support;

    public P012PromotionController(
            PromotionService promotions, Phase11ApiSupport support) {
        this.promotions = promotions;
        this.support = support;
    }

    @PostMapping
    public Phase11Record create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PromotionService.CreateCommand command) {
        support.requireAction(principal, CREATE, "P012");
        support.requireData(
                principal,
                CREATE,
                support.target(
                        principal,
                        command.ownerCenterId(),
                        command.ownerEmployeeId()),
                "P012");
        support.audit(principal, "P012_CREATE_ATTEMPT", "hr.promotion_request", null);
        Phase11Record result = promotions.create(
                support.context(principal),
                idempotencyKey,
                support.hash(command, "P012"),
                command);
        support.audit(principal, "P012_CREATED", "hr.promotion_request", result.id());
        return result;
    }

    @GetMapping
    public List<Phase11Record> list(
            @AuthenticationPrincipal SessionPrincipal principal) {
        boolean read = support.allowed(principal, READ);
        boolean manage = manageAllowed(principal);
        boolean monitor = support.allowed(principal, MONITOR);
        if (!read && !manage && !monitor) {
            throw denied("no P012 read surface is granted");
        }
        return promotions.list(support.context(principal)).stream()
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
            throw denied("P012 data scope denied");
        }
        support.audit(principal, "P012_READ", "hr.promotion_request", id);
        return projected;
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public Phase11Record action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PromotionService.ActionCommand command) {
        String action = support.safeAction(actionCode);
        String permission = actionPermission(action);
        support.requireAction(principal, permission, "P012");
        Phase11Record current = required(principal, id);
        support.requireData(
                principal,
                permission,
                support.target(principal, current),
                "P012");
        support.audit(
                principal,
                "P012_ACTION_ATTEMPT_" + action,
                "hr.promotion_request",
                id);
        Phase11Record result = promotions.act(
                support.context(principal),
                id,
                action,
                idempotencyKey,
                support.hash(Map.of("action", action, "body", command), "P012"),
                command);
        support.audit(
                principal,
                "P012_ACTION_" + action,
                "hr.promotion_request",
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
        return support.allowed(principal, REVIEW)
                || support.allowed(principal, APPOINT)
                || support.allowed(principal, ACTIVATE);
    }

    private boolean anyManageData(
            SessionPrincipal principal, AuthorizationTarget target) {
        return support.allowedData(principal, REVIEW, target)
                || support.allowedData(principal, APPOINT, target)
                || support.allowedData(principal, ACTIVATE, target);
    }

    private Phase11Record required(SessionPrincipal principal, UUID id) {
        return promotions
                .find(support.context(principal), id)
                .orElseThrow(() ->
                        new IllegalArgumentException("P012 promotion request not found"));
    }

    private static String actionPermission(String action) {
        return switch (action) {
            case "PASS_ELIGIBILITY",
                    "SUBMIT_ASSESSMENT",
                    "VERIFY_POSITION_BUDGET",
                    "COMPLETE_REVIEW" -> REVIEW;
            case "APPROVE_PROMOTION",
                    "COMPLETE_NOTICE",
                    "COMPLETE_VALIDATION" -> APPOINT;
            case "CONFIRM_APPOINTMENT" -> READ;
            case "ACTIVATE_APPOINTMENT" -> ACTIVATE;
            default -> throw new IllegalArgumentException("P012 action is invalid");
        };
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("P012 authorization denied: " + reason);
    }
}
