package cn.shangjingu.platform.api.phase05;

import cn.shangjingu.platform.api.security.JdbcSecurityAuditService;
import cn.shangjingu.platform.api.security.SessionPrincipal;
import cn.shangjingu.platform.audit.SensitiveExportService;
import cn.shangjingu.platform.core.database.DatabaseSecurityContext;
import cn.shangjingu.platform.iam.authorization.AuthorizationDecision;
import cn.shangjingu.platform.iam.authorization.AuthorizationService;
import cn.shangjingu.platform.iam.stepup.StepUpService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
@RequestMapping("/api/v1/phase05/sensitive-exports")
public final class SensitiveExportController {
    private static final String READ = "phase05.p019.read";
    private static final String WRITE = "phase05.p019.write";
    private static final String DOWNLOAD = "phase05.p019.download";

    private final SensitiveExportService exports;
    private final AuthorizationService authorization;
    private final StepUpService stepUp;
    private final JdbcSecurityAuditService audit;
    private final ObjectMapper mapper;

    public SensitiveExportController(
            SensitiveExportService exports,
            AuthorizationService authorization,
            StepUpService stepUp,
            JdbcSecurityAuditService audit,
            ObjectMapper mapper) {
        this.exports = exports;
        this.authorization = authorization;
        this.stepUp = stepUp;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostMapping
    public SensitiveExportService.ExportRequest create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody SensitiveExportService.CreateCommand command) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P019_CREATE_ATTEMPT", "audit.data_export_request", null);
        return exports.create(context(principal), idempotencyKey, hash(command), command);
    }

    @GetMapping("/{id}")
    public SensitiveExportService.ExportRequest get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        return exports.find(context(principal), id)
                .orElseThrow(() -> new IllegalArgumentException("sensitive export request not found"));
    }

    @GetMapping("/{id}/items")
    public List<SensitiveExportService.ExportItem> items(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id) {
        require(authorization.authorizeAction(principal.context(), READ));
        return exports.items(context(principal), id);
    }

    @PostMapping("/{id}/advance")
    public SensitiveExportService.ExportRequest advance(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody VersionStateRequest request) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P019_ADVANCE_ATTEMPT", "audit.data_export_request", id);
        return exports.advance(context(principal), id, request.expectedVersion(), request.requestedStatus());
    }

    @PostMapping("/{id}/download-grant")
    public SensitiveExportService.DownloadGrant downloadGrant(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestHeader("Step-Up-Ticket") String stepUpTicket,
            @RequestBody VersionRequest request) {
        require(authorization.authorizeAction(principal.context(), DOWNLOAD));
        stepUp.requireAndConsume(stepUpTicket, principal.context(), downloadPurpose(id));
        audit.recordOperation(principal.context(), "P019_DOWNLOAD_GRANT_ATTEMPT", "audit.data_export_request", id);
        return exports.issueAuditedDownload(context(principal), id, request.expectedVersion());
    }

    @PostMapping("/{id}/expire")
    public SensitiveExportService.ExportRequest expire(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody VersionRequest request) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P019_EXPIRE_DESTROY_ATTEMPT", "audit.data_export_request", id);
        return exports.expireAndDestroy(context(principal), id, request.expectedVersion());
    }

    @PostMapping("/{id}/close")
    public SensitiveExportService.ExportRequest close(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID id,
            @RequestBody VersionRequest request) {
        require(authorization.authorizeAction(principal.context(), WRITE));
        audit.recordOperation(principal.context(), "P019_CLOSE_ATTEMPT", "audit.data_export_request", id);
        return exports.close(context(principal), id, request.expectedVersion());
    }

    public static String downloadPurpose(UUID id) {
        return "P019_DOWNLOAD:" + id;
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("request cannot be hashed", ex);
        }
    }

    public record VersionStateRequest(int expectedVersion, String requestedStatus) {
    }

    public record VersionRequest(int expectedVersion) {
    }
}
