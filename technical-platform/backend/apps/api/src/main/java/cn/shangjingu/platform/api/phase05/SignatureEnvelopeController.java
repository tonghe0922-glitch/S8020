package cn.shangjingu.platform.api.phase05;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.document.SignatureEnvelopeService;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
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
@RequestMapping("/api/v1/phase05/signatures/envelopes")
public class SignatureEnvelopeController {
    private static final String READ = "phase05.p017.read";
    private static final String WRITE = "phase05.p017.write";
    private static final String CALLBACK = "phase05.p017.provider-callback";

    private final SignatureEnvelopeService signatures;
    private final AuthorizationService authorization;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public SignatureEnvelopeController(
            SignatureEnvelopeService signatures,
            AuthorizationService authorization,
            JdbcSecurityAuditService audit,
            ObjectMapper mapper) {
        this.signatures = signatures;
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping
    public SignatureEnvelopeService.Envelope create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SignatureEnvelopeService.CreateCommand command) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P017_CREATE_ATTEMPT", "document.signature_envelope", null);
        return signatures.create(context(principal), idempotencyKey, hash(command), command);
    }

    @GetMapping("/{id}")
    public SignatureEnvelopeService.Envelope get(
            @AuthenticationPrincipal SessionPrincipal principal, @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        return signatures
                .find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("signature envelope not found"));
    }

    @PostMapping("/{id}/advance")
    public SignatureEnvelopeService.Envelope advance(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody AdvanceRequest request) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P017_ADVANCE_ATTEMPT", "document.signature_envelope", id);
        return signatures.advance(context(principal), id, request.expectedVersion(), request.requestedStatus());
    }

    @PostMapping("/{id}/provider-callback")
    public SignatureEnvelopeService.Envelope providerCallback(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Provider-Event-Key") String providerEventKey,
            @RequestBody CallbackRequest request) {
        require(authorization.authorizeAction(principal.context(), CALLBACK));
        audit.recordOperation(principal.context(), "P017_PROVIDER_CALLBACK_ATTEMPT", "document.signature_envelope", id);
        return signatures.verifyCallback(
                context(principal),
                id,
                request.expectedVersion(),
                providerEventKey,
                hash(request.evidence()),
                request.evidence());
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
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("request cannot be hashed", ex);
        }
    }

    public record AdvanceRequest(int expectedVersion, String requestedStatus) {}

    public record CallbackRequest(int expectedVersion, SignatureEnvelopeService.CallbackEvidence evidence) {}
}
