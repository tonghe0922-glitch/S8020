package cn.shangjingu.platform.api.phase09;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.application.PermissionRequestService;
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
@RequestMapping("/api/v1/processes/P002/permission-requests")
public class P002PermissionRequestController {
    private static final String SUBMIT = "p002.request.submit";
    private static final String READ = "p002.request.read";
    private static final String REVIEW = "p002.request.review";
    private static final String EXECUTE = "p002.request.execute";
    private static final String REVOKE = "p002.request.revoke";

    private final PermissionRequestService requests;
    private final AuthorizationService authorization;
    private final P002ReviewSeparationPolicy reviewSeparation;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public P002PermissionRequestController(
            PermissionRequestService requests,
            AuthorizationService authorization,
            P002ReviewSeparationPolicy reviewSeparation,
            JdbcSecurityAuditService audit,
            ObjectMapper mapper) {
        this.requests = requests;
        this.authorization = authorization;
        this.reviewSeparation = reviewSeparation;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping
    public PermissionRequestService.PermissionRequest create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PermissionRequestService.CreateCommand command) {
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
        audit.recordOperation(principal.context(), "P002_CREATE_ATTEMPT", "iam.permission_request", null);
        PermissionRequestService.PermissionRequest created =
                requests.create(context(principal), idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P002_CREATED", "iam.permission_request", created.id());
        return created;
    }

    @GetMapping("/{id}")
    public PermissionRequestService.PermissionRequest get(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        PermissionRequestService.PermissionRequest request = requests.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P002 permission request not found"));
        require(authorization.authorizeData(principal.context(), READ, target(request)));
        audit.recordOperation(principal.context(), "P002_READ", "iam.permission_request", id);
        return request;
    }

    @GetMapping
    public List<PermissionRequestService.PermissionRequest> list(@AuthenticationPrincipal SessionPrincipal principal) {
        require(authorization.authorizeAction(principal.context(), READ));
        List<PermissionRequestService.PermissionRequest> visible = requests.list(context(principal)).stream()
                .filter(request -> authorization
                        .authorizeData(principal.context(), READ, target(request))
                        .allowed())
                .toList();
        audit.recordOperation(principal.context(), "P002_LIST", "iam.permission_request", null);
        return visible;
    }

    @PostMapping("/{id}/actions/review")
    public PermissionRequestService.PermissionRequest review(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PermissionRequestService.ReviewCommand command) {
        PermissionRequestService.PermissionRequest current = scoped(principal, id, REVIEW);
        DatabaseSecurityContext actor = context(principal);
        reviewSeparation.requireDistinctReviewer(actor, current, idempotencyKey);
        audit.recordOperation(principal.context(), "P002_REVIEW_ATTEMPT", "iam.permission_request", id);
        PermissionRequestService.PermissionRequest result =
                requests.review(actor, current.id(), idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P002_REVIEWED", "iam.permission_request", id);
        return result;
    }

    @PostMapping("/{id}/actions/execute")
    public PermissionRequestService.PermissionRequest execute(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PermissionRequestService.ActionCommand command) {
        PermissionRequestService.PermissionRequest current = scoped(principal, id, EXECUTE);
        audit.recordOperation(principal.context(), "P002_EXECUTE_ATTEMPT", "iam.permission_request", id);
        PermissionRequestService.PermissionRequest result =
                requests.execute(context(principal), current.id(), idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P002_EXECUTED", "iam.permission_request", id);
        return result;
    }

    @PostMapping("/{id}/actions/revoke")
    public PermissionRequestService.PermissionRequest revoke(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PermissionRequestService.ActionCommand command) {
        PermissionRequestService.PermissionRequest current = scoped(principal, id, REVOKE);
        audit.recordOperation(principal.context(), "P002_REVOKE_ATTEMPT", "iam.permission_request", id);
        PermissionRequestService.PermissionRequest result =
                requests.revoke(context(principal), current.id(), idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P002_REVOKED", "iam.permission_request", id);
        return result;
    }

    private PermissionRequestService.PermissionRequest scoped(SessionPrincipal principal, UUID id, String permission) {
        require(authorization.authorizeAction(principal.context(), permission));
        PermissionRequestService.PermissionRequest request = requests.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P002 permission request not found"));
        require(authorization.authorizeData(principal.context(), permission, target(request)));
        return request;
    }

    private static AuthorizationTarget target(PermissionRequestService.PermissionRequest request) {
        return new AuthorizationTarget(
                request.tenantId(),
                request.ownerEmployeeId(),
                request.ownerCenterId(),
                null,
                request.ownerEmployeeId());
    }

    private static DatabaseSecurityContext context(SessionPrincipal principal) {
        var s = principal.context();
        return new DatabaseSecurityContext(
                s.tenantId(), s.userId(), s.identityId(), s.employeeId(), s.appointmentId(), s.orgId(), s.positionId());
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) throw new AccessDeniedException("P002 authorization denied: " + decision.reason());
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("P002 request cannot be hashed", ex);
        }
    }
}
