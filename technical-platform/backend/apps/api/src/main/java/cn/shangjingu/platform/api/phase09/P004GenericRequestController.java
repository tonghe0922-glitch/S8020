package cn.shangjingu.platform.api.phase09;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.workflow.GenericRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
@RequestMapping("/api/v1/processes/P004/generic-requests")
public final class P004GenericRequestController {
    private static final String SUBMIT = "p004.request.submit";
    private static final String READ = "p004.request.read";
    private static final String ACT = "p004.request.act";

    private final GenericRequestService requests;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public P004GenericRequestController(
            GenericRequestService requests, AuthorizationService authorization,
            JdbcSecurityAuditService audit, ObjectMapper mapper) {
        this.requests = requests;
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping
    public GenericRequestService.GenericRequest create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody GenericRequestService.CreateCommand command) {
        require(authorization.authorizeAction(principal.context(), SUBMIT));
        require(authorization.authorizeData(principal.context(), SUBMIT, new AuthorizationTarget(
                principal.context().tenantId(), principal.context().employeeId(), principal.context().orgId(),
                principal.context().positionId(), principal.context().employeeId())));
        audit.recordOperation(principal.context(), "P004_CREATE_ATTEMPT", "workflow.generic_request", null);
        GenericRequestService.GenericRequest created = requests.create(
                context(principal), idempotencyKey, hash(command), command);
        audit.recordOperation(principal.context(), "P004_CREATED", "workflow.generic_request", created.id());
        return view(principal, created);
    }

    @GetMapping("/{id}")
    public GenericRequestService.GenericRequest get(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        GenericRequestService.GenericRequest request = requests.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P004 generic request not found"));
        require(authorization.authorizeData(principal.context(), READ, target(request)));
        audit.recordOperation(principal.context(), "P004_READ", "workflow.generic_request", id);
        return view(principal, request);
    }

    @GetMapping
    public List<GenericRequestService.GenericRequest> list(@AuthenticationPrincipal SessionPrincipal principal) {
        require(authorization.authorizeAction(principal.context(), READ));
        List<GenericRequestService.GenericRequest> visible = requests.list(context(principal)).stream()
                .filter(request -> authorization.authorizeData(principal.context(), READ, target(request)).allowed())
                .map(request -> view(principal, request))
                .toList();
        audit.recordOperation(principal.context(), "P004_LIST", "workflow.generic_request", null);
        return visible;
    }

    @PostMapping("/{id}/actions/{actionCode}")
    public GenericRequestService.GenericRequest act(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @PathVariable String actionCode,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody GenericRequestService.ActionCommand command) {
        GenericRequestService.GenericRequest current = requests.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("P004 generic request not found"));
        String permission = requests.isApplicantAction(current, actionCode) ? SUBMIT : ACT;
        require(authorization.authorizeAction(principal.context(), permission));
        require(authorization.authorizeData(principal.context(), permission, target(current)));
        audit.recordOperation(principal.context(), "P004_ACTION_ATTEMPT_" + safeAction(actionCode),
                "workflow.generic_request", id);
        GenericRequestService.GenericRequest result = requests.act(
                context(principal), id, actionCode, idempotencyKey,
                hash(Map.of("actionCode", safeAction(actionCode), "body", command)), command);
        audit.recordOperation(principal.context(), "P004_ACTION_" + safeAction(actionCode),
                "workflow.generic_request", id);
        return view(principal, result);
    }

    private GenericRequestService.GenericRequest view(
            SessionPrincipal principal, GenericRequestService.GenericRequest request) {
        boolean owner = principal.context().employeeId().equals(request.ownerEmployeeId());
        boolean businessActor = authorization.authorizeAction(principal.context(), ACT).allowed();
        if (owner || businessActor) return request;
        GenericRequestService.GenericRequest metadata = request.metadataOnly();
        return new GenericRequestService.GenericRequest(
                metadata.id(), metadata.tenantId(), metadata.businessNo(), metadata.workflowInstanceId(),
                metadata.workflowInstanceNo(), metadata.currentNodeCode(), metadata.status(), metadata.versionNo(),
                metadata.requestType(), metadata.subject(), null, null, metadata.businessDate(), metadata.actualAmount(),
                metadata.actualEndAt(), metadata.ownerCenterId(), metadata.ownerEmployeeId(), metadata.priority(),
                metadata.riskLevel(), metadata.amount(), metadata.initialSubmissionId(), metadata.initialSubmissionNo(),
                metadata.initialFormVersion(), null, metadata.updatedAt());
    }

    private static AuthorizationTarget target(GenericRequestService.GenericRequest request) {
        return new AuthorizationTarget(request.tenantId(), request.ownerEmployeeId(), request.ownerCenterId(), null,
                request.ownerEmployeeId());
    }

    private static DatabaseSecurityContext context(SessionPrincipal principal) {
        var s = principal.context();
        return new DatabaseSecurityContext(
                s.tenantId(), s.userId(), s.identityId(), s.employeeId(), s.appointmentId(), s.orgId(), s.positionId());
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) throw new AccessDeniedException("P004 authorization denied: " + decision.reason());
    }

    private static String safeAction(String actionCode) {
        if (actionCode == null) return "UNKNOWN";
        String value = actionCode.trim().toUpperCase();
        return value.matches("[A-Z0-9_]{1,32}") ? value : "INVALID";
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("P004 request cannot be hashed", ex);
        }
    }
}
