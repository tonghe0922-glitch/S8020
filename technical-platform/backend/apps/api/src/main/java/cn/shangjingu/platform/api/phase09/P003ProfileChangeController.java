package cn.shangjingu.platform.api.phase09;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.hr.profile.ProfileChangeService;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
@RequestMapping("/api/v1/processes/P003/profile-changes")
public class P003ProfileChangeController {
    private static final String SUBMIT = "p003.change.submit";
    private static final String READ = "p003.change.read";
    private static final String REVIEW = "p003.change.review";
    private static final String APPLY = "p003.change.apply";

    private final ProfileChangeService changes;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public P003ProfileChangeController(
            ProfileChangeService changes,
            AuthorizationService authorization,
            JdbcSecurityAuditService audit,
            ObjectMapper mapper) {
        this.changes = changes;
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping
    public ProfileChangeService.ProfileChange create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ProfileChangeService.CreateCommand command) {
        require(authorization.authorizeAction(principal.context(), SUBMIT));
        require(authorization.authorizeData(
                principal.context(),
                SUBMIT,
                new AuthorizationTarget(
                        principal.context().tenantId(),
                        principal.context().employeeId(),
                        principal.context().orgId(),
                        principal.context().positionId(),
                        principal.context().employeeId())));
        audit.recordOperation(principal.context(), "P003_CREATE_ATTEMPT", "hr.employee_profile_change", null);
        ProfileChangeService.ProfileChange created =
                changes.create(context(principal), idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P003_CREATED", "hr.employee_profile_change", created.id());
        return created;
    }

    @GetMapping("/{id}")
    public ProfileChangeService.ProfileChange get(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        ProfileChangeService.ProfileChange change = changes.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P003 profile change not found"));
        require(authorization.authorizeData(principal.context(), READ, target(change)));
        audit.recordOperation(principal.context(), "P003_READ", "hr.employee_profile_change", id);
        return change;
    }

    @GetMapping
    public List<ProfileChangeService.ProfileChange> list(@AuthenticationPrincipal SessionPrincipal principal) {
        require(authorization.authorizeAction(principal.context(), READ));
        List<ProfileChangeService.ProfileChange> visible = changes.list(context(principal)).stream()
                .filter(change -> authorization
                        .authorizeData(principal.context(), READ, target(change))
                        .allowed())
                .toList();
        audit.recordOperation(principal.context(), "P003_LIST", "hr.employee_profile_change", null);
        return visible;
    }

    @PostMapping("/{id}/actions/review")
    public ProfileChangeService.ProfileChange review(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ProfileChangeService.ReviewCommand command) {
        ProfileChangeService.ProfileChange current = scoped(principal, id, REVIEW);
        audit.recordOperation(principal.context(), "P003_REVIEW_ATTEMPT", "hr.employee_profile_change", id);
        ProfileChangeService.ProfileChange result =
                changes.review(context(principal), current.id(), idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P003_REVIEWED", "hr.employee_profile_change", id);
        return result;
    }

    @PostMapping("/{id}/actions/apply")
    public ProfileChangeService.ProfileChange apply(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ProfileChangeService.ApplyCommand command) {
        ProfileChangeService.ProfileChange current = scoped(principal, id, APPLY);
        audit.recordOperation(principal.context(), "P003_APPLY_ATTEMPT", "hr.employee_profile_change", id);
        ProfileChangeService.ProfileChange result =
                changes.apply(context(principal), current.id(), idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P003_APPLIED", "hr.employee_profile_change", id);
        return result;
    }

    private ProfileChangeService.ProfileChange scoped(SessionPrincipal principal, UUID id, String permission) {
        require(authorization.authorizeAction(principal.context(), permission));
        ProfileChangeService.ProfileChange change = changes.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P003 profile change not found"));
        require(authorization.authorizeData(principal.context(), permission, target(change)));
        return change;
    }

    private static AuthorizationTarget target(ProfileChangeService.ProfileChange change) {
        return new AuthorizationTarget(
                change.tenantId(), change.ownerEmployeeId(), change.ownerCenterId(), null, change.ownerEmployeeId());
    }

    private static DatabaseSecurityContext context(SessionPrincipal principal) {
        var s = principal.context();
        return new DatabaseSecurityContext(
                s.tenantId(), s.userId(), s.identityId(), s.employeeId(), s.appointmentId(), s.orgId(), s.positionId());
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) throw new AccessDeniedException("P003 authorization denied: " + decision.reason());
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("P003 request cannot be hashed", ex);
        }
    }
}
