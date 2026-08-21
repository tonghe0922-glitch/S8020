package cn.shangjingu.platform.api.phase05;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.authorization.AuthorizationTarget;
import cn.shangjingu.platform.welfare.CareCaseService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
@RequestMapping("/api/v1/phase05/welfare/care-cases")
public final class WelfareCareCaseController {
    private static final String READ = "phase05.p016.read";
    private static final String WRITE = "phase05.p016.write";

    private final CareCaseService careCases;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper objectMapper;

    public WelfareCareCaseController(
            CareCaseService careCases,
            AuthorizationService authorization,
            JdbcSecurityAuditService audit,
            ObjectMapper objectMapper) {
        this.careCases = careCases;
        this.authorization = authorization;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public CareCaseService.CareCase create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CareCaseService.CreateCommand command) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        require(authorization.authorizeData(principal.context(), WRITE, new AuthorizationTarget(
                principal.context().tenantId(), command.ownerEmployeeId(), command.ownerCenterId(), null,
                command.ownerEmployeeId())));
        audit.recordOperation(principal.context(), "P016_CREATE_ATTEMPT", "welfare.care_case", null);
        return careCases.create(context(principal), idempotencyKey, hash(command), command);
    }

    @GetMapping("/{id}")
    public CareCaseService.CareCase get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        CareCaseService.CareCase careCase = careCases.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("care case not found"));
        require(authorization.authorizeData(principal.context(), READ, target(careCase)));
        return careCase;
    }

    @PostMapping("/{id}/advance")
    public CareCaseService.CareCase advance(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody AdvanceRequest request) {
        CareCaseService.CareCase current = careCases.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("care case not found"));
        require(authorization.authorizeData(principal.context(), WRITE, target(current)));
        audit.recordOperation(principal.context(), "P016_ADVANCE_ATTEMPT", "welfare.care_case", id);
        return careCases.advance(context(principal), id, request.expectedVersion(), request.requestedStatus(),
                request.resultSummary(), request.closureChecklist());
    }

    @PostMapping("/{id}/invoice-evidence/validate")
    public void validateInvoiceEvidence(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody CareCaseService.InvoiceEvidence invoice) {
        CareCaseService.CareCase current = careCases.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("care case not found"));
        require(authorization.authorizeData(principal.context(), WRITE, target(current)));
        audit.recordOperation(principal.context(), "P016_INVOICE_VALIDATE_ATTEMPT", "welfare.care_case", id);
        careCases.validateInvoiceEvidence(context(principal), id, invoice);
    }

    private AuthorizationTarget target(CareCaseService.CareCase careCase) {
        return new AuthorizationTarget(careCase.tenantId(), careCase.ownerEmployeeId(), careCase.ownerCenterId(), null,
                careCase.ownerEmployeeId());
    }

    private static DatabaseSecurityContext context(SessionPrincipal principal) {
        var s = principal.context();
        return new DatabaseSecurityContext(
                s.tenantId(), s.userId(), s.identityId(), s.employeeId(), s.appointmentId(), s.orgId(), s.positionId());
    }

    private static void require(AuthorizationDecision decision) {
        if (!decision.allowed()) {
            throw new AccessDeniedException("phase05 authorization denied: " + decision.reason());
        }
    }

    private String hash(Object value) {
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("request cannot be hashed", ex);
        }
    }

    public record AdvanceRequest(
            int expectedVersion,
            String requestedStatus,
            String resultSummary,
            CareCaseService.ClosureChecklist closureChecklist) {
    }
}
