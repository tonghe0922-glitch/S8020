package cn.shangjingu.platform.api.phase10;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.LearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
@RequestMapping("/api/v1/processes/P010")
public final class P010LearningController {
    public static final String READ = "p010.learning.read";
    public static final String MANAGE = "p010.learning.manage";
    public static final String COMPLETE = "p010.learning.complete";
    public static final String EXAM = "p010.learning.exam";
    public static final String CERTIFY = "p010.learning.certify";
    public static final String MONITOR = "p010.learning.monitor";

    private final LearningService learning;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public P010LearningController(
            LearningService learning,
            AuthorizationService authorization,
            JdbcSecurityAuditService audit,
            ObjectMapper mapper) {
        this.learning = learning;
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping("/assignments")
    public LearningService.Aggregate create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody LearningService.CreateCommand command) {
        require(authorization.authorizeAction(principal.context(), MANAGE));
        require(
                authorization.authorizeData(
                        principal.context(),
                        MANAGE,
                        new AuthorizationTarget(
                                principal.context().tenantId(),
                                command.ownerEmployeeId(),
                                command.ownerCenterId(),
                                principal.context().positionId(),
                                command.ownerEmployeeId())));
        audit.recordOperation(
                principal.context(),
                "P010_CREATE_ATTEMPT",
                "learning.learning_assignment",
                null);
        LearningService.Aggregate result =
                learning.create(context(principal), key, hash(command), command);
        audit.recordOperation(
                principal.context(),
                "P010_CREATED",
                "learning.learning_assignment",
                result.record().id());
        return result;
    }

    @GetMapping("/assignments")
    public List<LearningService.Aggregate> list(
            @AuthenticationPrincipal SessionPrincipal principal) {
        boolean read = allowed(principal, READ);
        boolean manage = allowed(principal, MANAGE);
        boolean certify = allowed(principal, CERTIFY);
        boolean monitor = allowed(principal, MONITOR);
        if (!read && !manage && !certify && !monitor) {
            throw denied("no P010 read surface");
        }
        List<LearningService.Aggregate> result =
                learning.list(context(principal)).stream()
                        .map(
                                assignment ->
                                        project(
                                                principal,
                                                assignment,
                                                read,
                                                manage,
                                                certify,
                                                monitor))
                        .filter(java.util.Objects::nonNull)
                        .toList();
        audit.recordOperation(
                principal.context(),
                "P010_LIST",
                "learning.learning_assignment",
                null);
        return result;
    }

    @GetMapping("/assignments/{id}")
    public LearningService.Aggregate get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        boolean read = allowed(principal, READ);
        boolean manage = allowed(principal, MANAGE);
        boolean certify = allowed(principal, CERTIFY);
        boolean monitor = allowed(principal, MONITOR);
        if (!read && !manage && !certify && !monitor) {
            throw denied("no P010 read surface");
        }
        LearningService.Aggregate assignment =
                learning.find(context(principal), id)
                        .orElseThrow(
                                () -> new IllegalArgumentException("P010 assignment not found"));
        LearningService.Aggregate projected =
                project(principal, assignment, read, manage, certify, monitor);
        if (projected == null) {
            throw denied("P010 data scope denied");
        }
        audit.recordOperation(
                principal.context(),
                "P010_READ",
                "learning.learning_assignment",
                id);
        return projected;
    }

    @PostMapping("/assignments/{id}/learning-progress")
    public LearningService.Aggregate progress(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody LearningService.ProgressCommand command) {
        require(authorization.authorizeAction(principal.context(), COMPLETE));
        owned(principal, id, COMPLETE);
        audit.recordOperation(
                principal.context(),
                "P010_PROGRESS_ATTEMPT",
                "learning.learning_assignment",
                id);
        LearningService.Aggregate result =
                learning.progress(context(principal), id, key, hash(command), command);
        audit.recordOperation(
                principal.context(),
                "P010_PROGRESS",
                "learning.learning_assignment",
                id);
        return result;
    }

    @PostMapping("/assignments/{id}/exam")
    public LearningService.Aggregate exam(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody LearningService.ExamCommand command) {
        require(authorization.authorizeAction(principal.context(), EXAM));
        owned(principal, id, EXAM);
        audit.recordOperation(
                principal.context(),
                "P010_EXAM_ATTEMPT",
                "learning.learning_assignment",
                id);
        LearningService.Aggregate result =
                learning.exam(context(principal), id, key, hash(command), command);
        audit.recordOperation(
                principal.context(),
                "P010_EXAM",
                "learning.learning_assignment",
                id);
        return result;
    }

    @PostMapping("/assignments/{id}/practical")
    public LearningService.Aggregate practical(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody LearningService.PracticalCommand command) {
        require(authorization.authorizeAction(principal.context(), COMPLETE));
        owned(principal, id, COMPLETE);
        audit.recordOperation(
                principal.context(),
                "P010_PRACTICAL_ATTEMPT",
                "learning.learning_assignment",
                id);
        LearningService.Aggregate result =
                learning.practical(context(principal), id, key, hash(command), command);
        audit.recordOperation(
                principal.context(),
                "P010_PRACTICAL",
                "learning.learning_assignment",
                id);
        return result;
    }

    @PostMapping("/assignments/{id}/actions/{actionCode}")
    public LearningService.Aggregate action(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody LearningService.ActionCommand command) {
        String action = safe(actionCode);
        String permission =
                action.equals("CERTIFY") || action.equals("RETURN_FOR_TRAINING")
                        ? CERTIFY
                        : MANAGE;
        require(authorization.authorizeAction(principal.context(), permission));
        LearningService.Aggregate assignment =
                learning.find(context(principal), id)
                        .orElseThrow(
                                () -> new IllegalArgumentException("P010 assignment not found"));
        require(
                authorization.authorizeData(
                        principal.context(),
                        permission,
                        target(principal, assignment.record())));
        audit.recordOperation(
                principal.context(),
                "P010_ACTION_ATTEMPT_" + action,
                "learning.learning_assignment",
                id);
        LearningService.Aggregate result =
                learning.action(
                        context(principal), id, action, key, hash(command), command);
        audit.recordOperation(
                principal.context(),
                "P010_ACTION_" + action,
                "learning.learning_assignment",
                id);
        return result;
    }

    private LearningService.Aggregate owned(
            SessionPrincipal principal, UUID id, String permission) {
        LearningService.Aggregate assignment =
                learning.find(context(principal), id)
                        .orElseThrow(
                                () -> new IllegalArgumentException("P010 assignment not found"));
        if (!principal.context().employeeId().equals(assignment.record().ownerEmployeeId())) {
            throw denied("employee operation is self-only");
        }
        require(
                authorization.authorizeData(
                        principal.context(),
                        permission,
                        target(principal, assignment.record())));
        return assignment;
    }

    private LearningService.Aggregate project(
            SessionPrincipal principal,
            LearningService.Aggregate assignment,
            boolean read,
            boolean manage,
            boolean certify,
            boolean monitor) {
        AuthorizationTarget target = target(principal, assignment.record());
        if (manage
                && authorization.authorizeData(principal.context(), MANAGE, target).allowed()) {
            return assignment;
        }
        if (certify
                && authorization.authorizeData(principal.context(), CERTIFY, target).allowed()) {
            return assignment;
        }
        if (read
                && principal.context().employeeId().equals(assignment.record().ownerEmployeeId())
                && authorization.authorizeData(principal.context(), READ, target).allowed()) {
            return assignment;
        }
        if (monitor
                && authorization.authorizeData(principal.context(), MONITOR, target).allowed()) {
            return assignment.metadataOnly();
        }
        return null;
    }

    private static AuthorizationTarget target(
            SessionPrincipal principal, LearningService.LearningRecord record) {
        return new AuthorizationTarget(
                record.tenantId(),
                record.ownerEmployeeId(),
                record.ownerCenterId(),
                principal.context().positionId(),
                record.ownerEmployeeId());
    }

    private boolean allowed(SessionPrincipal principal, String permission) {
        return authorization.authorizeAction(principal.context(), permission).allowed();
    }

    private static DatabaseSecurityContext context(SessionPrincipal principal) {
        var session = principal.context();
        return new DatabaseSecurityContext(
                session.tenantId(),
                session.userId(),
                session.identityId(),
                session.employeeId(),
                session.appointmentId(),
                session.orgId(),
                session.positionId());
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) {
            throw denied(decision.reason().toString());
        }
    }

    private static AccessDeniedException denied(String reason) {
        return new AccessDeniedException("P010 authorization denied: " + reason);
    }

    private static String safe(String action) {
        if (action == null) {
            return "INVALID";
        }
        String normalized = action.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9_]{1,40}") ? normalized : "INVALID";
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(mapper.writeValueAsBytes(value)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("P010 request cannot be hashed", exception);
        }
    }
}
